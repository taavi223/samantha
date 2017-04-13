package org.grouplens.samantha.ephemeral;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.Ordering;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.math3.linear.ArrayRealVector;
import org.apache.commons.math3.linear.RealVector;
import org.grouplens.samantha.ephemeral.model.CustomSVDFeature;
import org.grouplens.samantha.modeler.featurizer.FeatureExtractorUtilities;
import org.grouplens.samantha.modeler.svdfeature.SVDFeatureKey;
import org.grouplens.samantha.server.common.JsonHelpers;
import org.grouplens.samantha.server.exception.BadRequestException;
import org.grouplens.samantha.server.exception.ConfigurationException;
import org.grouplens.samantha.server.io.RequestContext;
import org.grouplens.samantha.server.predictor.Prediction;
import org.grouplens.samantha.server.predictor.Predictor;
import org.grouplens.samantha.server.ranker.AbstractRanker;
import org.grouplens.samantha.server.ranker.RankedResult;
import org.grouplens.samantha.server.ranker.RankerUtilities;
import org.grouplens.samantha.server.retriever.RetrievedResult;
import org.grouplens.samantha.server.retriever.RetrieverUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.Configuration;
import play.libs.Json;

import java.text.DecimalFormat;
import java.text.Format;
import java.util.*;
import java.util.stream.Collectors;

public class EphemeralRanker extends AbstractRanker {
    private final Predictor predictor;
    private final CustomSVDFeature svdFeature;
    private final double revertToMeanConstant;
    private final double revertToMeanFraction;
    private final int minRecentRatings;
    private final int maxRecentRatings;
    private final double excludeRecentRatingsBelow;
    private final Map<Integer, Double> preferenceWeights;
    private final Map<Integer, List<SelectionCriteria>> selectionCriteriaMap;
    private final Map<Integer, Integer> numRoundsToExclude;
    private final Map<String, Integer> expt;
    private Random random;
    private Format d = new DecimalFormat("#.###");

    private static Logger logger = LoggerFactory.getLogger(EphemeralRanker.class);


    public EphemeralRanker(
            Configuration config,
            Predictor predictor,
            CustomSVDFeature svdFeature,
            double revertToMeanConstant,
            double revertToMeanFraction,
            int minRecentRatings,
            int maxRecentRatings,
            double excludeRecentRatingsBelow,
            Map<Integer, Double> preferenceWeights,
            Map<Integer, List<SelectionCriteria>> selectionCriteriaMap,
            Map<Integer, Integer> numRoundsToExclude,
            Map<String, Integer> expt,
            long seed) {
        super(config);
        this.predictor = predictor;
        this.svdFeature = svdFeature;
        this.revertToMeanConstant = revertToMeanConstant;
        this.revertToMeanFraction = revertToMeanFraction;
        this.minRecentRatings = minRecentRatings;
        this.maxRecentRatings = maxRecentRatings;
        this.excludeRecentRatingsBelow = excludeRecentRatingsBelow;
        this.preferenceWeights = preferenceWeights;
        this.selectionCriteriaMap = selectionCriteriaMap;
        this.numRoundsToExclude = numRoundsToExclude;
        this.expt = expt;
        this.random = new Random(seed);
    }


    private RealVector getUserVector(int userId) {
        return getVector("userId", userId);
    }
    private RealVector getMovieVector(int movieId) {
        return getVector("movieId", movieId);
    }
    private RealVector getVector(String type, int id) {
        String name = SVDFeatureKey.FACTORS.get();
        String key = FeatureExtractorUtilities.composeKey(
                type,
                Integer.toString(id)
        );

        if (!svdFeature.containsKey(name, key)) {
            throw new NoSuchElementException();
        }

        int idx = svdFeature.getIndexForKey(name, key);
        return svdFeature.getVectorVarByNameIndex(name, idx);
    }

//    // Positive feedback only version of computing desired vector.
//    private RealVector computeDesiredVec(RealVector seed, List<Map<Integer, List<Integer>>> roundsList) {
//
//        // Iterate over each round, adding the user's preferences to the
//        // "desired" vector and more heavily weighting more recent rounds.
//        int i = 0;
//
//        for (Map<Integer, List<Integer>> round : roundsList) {
//            double roundCount = 0;
//            RealVector roundVec = new ArrayRealVector(seed.getDimension());
//
//            logger.info("Round {} Intermediate Vector Magnitude: {}", i, seed.getNorm());
//            i++;
//
//            // Each entry in round is a map from preference to list of movie ids
//            for (Map.Entry<Integer, List<Integer>> entry : round.entrySet()) {
//
//                // Get the weight for this preference
//                Double w = preferenceWeights.getOrDefault(entry.getKey(), 0.0);
//
//                // If the weight is zero, don't bother adding up the vectors.
//                if (w == 0.0) { continue; }
//
//                List<Integer> movies = entry.getValue();
//                RealVector prefVec = movies.stream()
//                        .map(x -> getMovieVector(x))
//                        .reduce(
//                                new ArrayRealVector(seed.getDimension()),
//                                (x, y) -> x.put(y)
//                        );
//
//                // Multiply by the configured weight for this preference value...
//                roundVec = roundVec.put(prefVec.mapMultiply(w));
//                roundCount += Math.abs(w) * movies.size();
//            }
//
//            // Average over all the movie vectors
//            roundVec.mapDivideToSelf(roundCount);
//
//            // Discount previous rounds by the following amount.
//            double discount;
//            if (halflife < 0.0) { // All rounds weighted equally,
//                discount = 1.0;
//            } else if (halflife == 0.0) { // Ignore any previous rounds
//                discount = 0.0;
//            } else { // Discount previous rounds according to the given halflife
//                discount = Math.pow(0.5, roundCount / halflife);
//            }
//
//            seed = seed.mapMultiply(discount).put(roundVec);
//
//        }
//        logger.info("Desired Vector Magnitude: {}", seed.getNorm());
//
//        // Normalize vector to keep dot products reasonable.
//        seed.mapDivideToSelf(seed.getNorm());
//        return seed;
//    }

    private RealVector computeDesiredVec(RealVector initialVec, List<Map<Integer, List<Integer>>> roundsList) {
        RealVector currentVec = initialVec.unitVector();
        RealVector defaultVec = svdFeature.getAverageUserVector().unitVector();

        // Iterate over each round, adding the user's preferences to the
        // "desired" vector and more heavily weighting more recent rounds.
        int i = 0;
        for (Map<Integer, List<Integer>> round : roundsList.subList(i, roundsList.size())) {
            i++;
            final RealVector initialRoundVec = currentVec;

            Map<Integer, RealVector> dVecMap = round.values().stream()
                    .flatMap(x -> x.stream())
                    .collect(Collectors.toMap(
                            x -> x,
                            x -> {
                                try {
                                    return getMovieVector(x)
                                            .mapDivide(getMovieVector(x).dotProduct(initialRoundVec))
                                            .subtract(initialRoundVec);
                                } catch (NoSuchElementException e) {
                                    // If a vector isn't in the model, pretend it doesn't exist!
                                    return initialVec.mapMultiply(0.0);
                                }
                            }
                    ));

            // Get minimum and maximum vector magnitudes.
            DoubleSummaryStatistics vectorNorms = dVecMap.values().stream()
                    .mapToDouble(v -> v.getNorm())
                    .filter(x -> x > 0)
                    .summaryStatistics();
            if (vectorNorms.getCount() == 0) {
                throw new BadRequestException("No nonzero vectors in round " + i);
            }

            double selectedNorm = 0.0;
            double totalNorm = vectorNorms.getSum();
            double maxNorm = vectorNorms.getMin();
            double minNorm = vectorNorms.getMax();

//            double distanceNumerator = 0.0;
//            double distanceDenominator = 0.0;
            double movementNumerator = 0.0;
            double movementDenominator = 0.0;
            RealVector vNumerator = new ArrayRealVector(currentVec.getDimension(), 0.0);
            double vDenominator = 0.0;

            for (Map.Entry<Integer, List<Integer>> entry : round.entrySet()) {
                double w = preferenceWeights.getOrDefault(entry.getKey(), 0.0);
                double ww = w * Math.sqrt(entry.getValue().size());

                if (ww == 0.0) { continue; } // skip if weight is zero...

                // Construct differential vectors
                List<RealVector> dVecs = entry.getValue().stream()
                        .map(x -> dVecMap.get(x))
                        .collect(Collectors.toList());
                Double dVecsNormSum = dVecs.stream().mapToDouble(v -> v.getNorm()).sum();

                // Weight differential vectors and put them to the numerator
                RealVector prefVec = dVecs.stream()
                        .map(v -> v.mapMultiply(ww))
                        .reduce((v1, v2) -> v1.add(v2))
                        .get();
                vNumerator = vNumerator.add(prefVec);

                // Add the absolute value of the preferenceWeights to the denominator
                vDenominator += dVecsNormSum * Math.abs(ww);


                // Calculate the movement pressure.
                if (ww > 0.0) {
                    // All positive vectors are given an equal weight of 1.
                    movementNumerator += Math.abs(w) * dVecsNormSum;
                    movementDenominator += Math.abs(w) * dVecs.size();

//                    distanceNumerator += Math.abs(ww) * dVecsNormSum;
//                    distanceDenominator += Math.abs(ww) * dVecs.size();
                } else { // ww < 0.0
                    // When minNorm is close to maxNorm, the weight of negative
                    // vectors will be close to 1, but all vectors will have
                    // similar magnitudes. When minNorm is substantially
                    // smaller than maxNorm, vectors will have a range of
                    // magnitudes, and the weight of distant negative vectors
                    // will be smaller than that of nearby negative vectors.
                    double totalNegWeight = dVecs.stream()
                            .map(v -> v.getNorm())
                            .mapToDouble(n -> Math.pow(0.1, (n - minNorm) / (2 * minNorm)))
                            .sum();
                    movementNumerator += Math.abs(w) * maxNorm * totalNegWeight;
                    movementDenominator += Math.abs(w) * totalNegWeight;

//                    distanceNumerator += Math.abs(ww) * dVecs.stream()
//                            .map(v -> v.getNorm())
//                            .mapToDouble(x -> maxNorm + minNorm - x)
//                            .sum();
//                    distanceDenominator += Math.abs(ww) * dVecs.size();
                }

                // Add up the selected vector norms, so we can calculate a percentage...
                selectedNorm += dVecsNormSum;
            }

            if (vDenominator !=0) {
                RealVector direction = vNumerator.mapDivide(vDenominator);

                double movement = movementNumerator / movementDenominator;
                double confidence = (1.0 / i) + Math.pow(selectedNorm / totalNorm, 1.0 / 3.0) * (i - 1.0) / i;
//                double distance = distanceNumerator / distanceDenominator;

                RealVector delta = direction.mapMultiply(movement * confidence);
//                // TODO: Which method works best for calculating movement distance?
//                RealVector delta = direction.mapMultiply(distance);
//                RealVector delta = direction.mapMultiply(distance * confidence);

                currentVec = currentVec.add(delta);
                currentVec = currentVec.unitVector();
            }

            double revertToMeanMultiplier = revertToMeanConstant;
            if (revertToMeanFraction != 0) {
                // getDistanceMetric("cosine", ...) will return a value between 0 and 1, where:
                // >>> 0 indicates the vectors are in the same direction;
                // >>> 0.5 indicates orthogonal vectors; and
                // >>> 1 indicates vectors in the opposite direction.
                revertToMeanMultiplier += getDistanceMetricScore("cosine", currentVec, defaultVec) * revertToMeanFraction;
            }

            // Add in a little bit of the average user vector and renormalize.
            if (revertToMeanMultiplier != 0) {
                RealVector previous =  currentVec.copy();
                currentVec = currentVec.mapMultiply(1.0 - revertToMeanMultiplier).add(defaultVec.mapMultiply(revertToMeanMultiplier));
                currentVec = currentVec.unitVector();
//                logger.debug("Revert to mean moved vector by cosine of {}", d.format(previous.cosine(currentVec)));
            }

//            if (i == 1) {
//                logger.debug("Cosine of {} from initialVec to round 1", d.format(currentVec.cosine(initialRoundVec)));
//            } else {
//                logger.debug("Cosine of {} from round {} to {}", d.format(currentVec.cosine(initialRoundVec)), i - 1, i);
//            }
        }

//        if (i > 1) {
//            logger.debug("Cosine of {} from initialVec to round {}", d.format(currentVec.cosine(initialVec)), i);
//        }

        return currentVec;
    }

    /////////////////////
    // REQUEST PARSING //
    /////////////////////

    private List<Map<Integer, List<Integer>>> parseRoundsList(JsonNode allRawRounds) {
        List<Map<Integer, List<Integer>>> allRounds = new ArrayList<>(allRawRounds.size());

        for (final JsonNode rawRound : allRawRounds) {
            Map<Integer, List<Integer>> round = preferenceWeights.keySet().stream()
                    .collect(Collectors.toMap(i -> i, i -> new ArrayList<>()));

            for (Iterator<Map.Entry<String, JsonNode>> it = rawRound.fields(); it.hasNext(); ) {
                try {
                    Map.Entry<String, JsonNode> entry = it.next();

                    int movieId = Integer.parseInt(entry.getKey());
                    int pref = entry.getValue().asInt();

                    if (!round.containsKey(pref)) {
                        throw new BadRequestException("preferences must be in " + round.keySet().toString());
                    }

                    round.get(pref).add(movieId);
                } catch (NumberFormatException e) {
                    throw new BadRequestException("movieIds must be integers");
                }
            }

            allRounds.add(round);
        }

        return allRounds;
    }

    private Set<Integer> getExclusions(List<Map<Integer, List<Integer>>> roundsList) {
        Set<Integer> exclusions = new HashSet<>();
        for (int i=0; i < roundsList.size(); i++) {
            for (Map.Entry<Integer, List<Integer>> entry : roundsList.get(i).entrySet()) {
                Integer back = numRoundsToExclude.get(entry.getKey());

                if (back < 0 || i >= roundsList.size() - back) {
                    exclusions.addAll(entry.getValue());
                }
            }
        }
        return exclusions;
    }


    public RankedResult rank(RetrievedResult retrievedResult, RequestContext requestContext) {
//        logger.debug("retrieved {} of {} items requested in universe", retrievedResult.getEntityList().size(), retrievedResult.getMaxHits());

        if (retrievedResult.getEntityList().isEmpty()) {
            logger.error("retriever model not loaded");
            throw new ConfigurationException("retriever model not loaded");
        }

        /*
         * STEP 1: Extract parameters from the request and transform them into
         * usable data structures.
         */

        JsonNode reqBody = requestContext.getRequestBody();

        // extract the user id
        int userId = JsonHelpers.getRequiredInt(reqBody, "userId");

        // Parse the list of rounds for the user
        List<Map<Integer, List<Integer>>> roundsList = new ArrayList<>();
        try {
            JsonNode rawRoundList = JsonHelpers.getRequiredArray(reqBody, "rounds");
            roundsList = parseRoundsList(rawRoundList);
//            logger.debug("Considering {} rounds", roundsList.size());
        } catch (BadRequestException e) {
//            logger.debug("request does not contain any rounds");
        }

        // Get recent highly rated movies and a list of all rated movies
        List<Integer> ratedMovieIds = new ArrayList<>();
        List<RealVector> recentlyRatedVecs = new ArrayList<>(3);
        try {
            JsonNode ratedMoviesNode = JsonHelpers.getRequiredJson(reqBody, "ratedMovies");
            if (!ratedMoviesNode.isArray()) { throw new BadRequestException("ratedMovies must be an array"); }
            for (JsonNode node : (ArrayNode) ratedMoviesNode) {
                try {
                    int movieId = JsonHelpers.getRequiredInt(node, "movieId");
                    ratedMovieIds.add(movieId);

                    double rating = JsonHelpers.getRequiredDouble(node, "rating");
                    if (recentlyRatedVecs.size() < maxRecentRatings && rating >= excludeRecentRatingsBelow) {
                        recentlyRatedVecs.add(getMovieVector(movieId));
                    }
                } catch (BadRequestException | NoSuchElementException e) {
                    // ignore movies not in model
                    continue;
                }
            }
//            logger.debug("User has rated {} movies", ratedMovieIds.size());
        } catch (BadRequestException e) {
//            logger.debug("request does not contain any rated movies");
        }

        // Get extra movies to ignore
        List<Integer> ignoredMovieIds = new ArrayList<>();
        try {
            ignoredMovieIds = JsonHelpers.getRequiredListOfInteger(reqBody, "ignoredMovieIds");
//            logger.debug("Ignoring {} movies", ignoredMovieIds.size());
        } catch (BadRequestException e) {
//            logger.debug("request does not contain any ignored movies");
        }

        /*
         * STEP 2: Calculate some variables for later use.
         */

        ObjectNode params = Json.newObject(); // extra params to return in response
        final int currentRoundNum = roundsList.size() + 1; // current round we're selecting for

        // Get the set of movies to exclude
        Set<Integer> exclusions = getExclusions(roundsList);
//        logger.debug("Excluding {} movies shown in previous rounds", exclusions.size());
        exclusions.addAll(ignoredMovieIds); // put any exclusions specified in the request

        // Get the universe of items, excluding previously shown items
        List<ObjectNode> universe = retrievedResult.getEntityList().stream()
                .filter(obj -> !exclusions.contains(obj.get("movieId").asInt()))
                .collect(Collectors.toList());

        /*
         * STEP 3: Pick which algorithm to use based on experimental condition.
         */

        if (expt.get("algorithm") == 0) { // real algorithm

            // Pick the initialVec based on the user's experimental condition
            RealVector initialVec = null;
            if (expt.get("origin") == 0 || expt.get("origin") == 3) { // average user vec
                initialVec = svdFeature.getAverageUserVector();
            } else if (expt.get("origin") == 1){ // current user's vec
                try {
                    initialVec = getUserVector(userId);
//                    logger.debug("Using a starting point personalized to long-term preference");
                } catch (NoSuchElementException e) {
                    logger.warn("User {} had no user vector to use for initial vector", userId);
                    initialVec = null; // Go with condition 0
                }
            } else if (expt.get("origin") == 2) { // average user's recent highly rated items
                if (recentlyRatedVecs.size() >= minRecentRatings) {
                    initialVec = recentlyRatedVecs.stream()
                            .reduce((v1, v2) -> v1.add(v2))
                            .get()
                            .mapDivide(recentlyRatedVecs.size());
//                    logger.debug("Using a starting point personalized to short-term preference with {} movies", recentlyRatedVecs.size());
                } else {
                    logger.warn("User {} had no positively rated movies to construct initial vector from", userId);
                    initialVec = null; // Go with condition 0
                }
            }

            // If the user didn't qualify for their exp. condition, disqualify them
            if (initialVec == null) {
                initialVec = svdFeature.getAverageUserVector();
                params.put("origin", 3); // Change user's condition
            }

            // Compute desired vector based on roundsList
            final RealVector desiredVec = computeDesiredVec(initialVec, roundsList);

            // Get the selection criteria for the current round, defaulting to
            // previous rounds as necessary.
            int selectionCriteriaNum = selectionCriteriaMap.keySet().stream()
                    .filter(x -> x <= currentRoundNum)
                    .mapToInt(x -> x)
                    .max().orElse(-1);
            if (selectionCriteriaNum <= 0) {
                throw new ConfigurationException("selectionCriteriaByRound must contain key 1");
            }
            List<SelectionCriteria> selectionCriteria = selectionCriteriaMap.get(selectionCriteriaNum);

            // This function call scores all items *in place*, and then returns
            // a filtered list of only the items that were successfully scored.
            // This filters out any items that are not in our model...
            universe = scoreAndFilterItems(desiredVec, universe);
            if (universe.isEmpty()) {
                logger.error("retriever and ephemeral model mismatch, no movies in both!");
                throw new ConfigurationException("retriever and ephemeral model mismatch, no movies in both!");
            }

            // Select items according to the selection criteria
            List<ObjectNode> selected = new ArrayList<>();
            for (SelectionCriteria criteria : selectionCriteria) {
//                logger.debug("Selecting {} of {} movies: ratedDropout={}, dropout={}",
//                        criteria.n, criteria.limit, criteria.ratedDropout, criteria.dropout);

                // Only re-sort the list when necessary
                Ordering<ObjectNode> ordering = RetrieverUtilities.jsonFieldOrdering(criteria.similarityMetric).reverse();
                if (!ordering.isOrdered(universe)) {
                    universe = ordering.immutableSortedCopy(universe);
                }

                try {
                    selectItems(selected, universe, ratedMovieIds, criteria);
                } catch (IndexOutOfBoundsException e) {
                    logger.error("Unable to select item for user {} for round {}.", userId, currentRoundNum);
                    throw e;
                }
            }

            // Return the selected items in the correct format.
            return response(selected, params);

        } else if (expt.get("algorithm") == 1) { // topn algorithm
            // Exclude rated items, just like we do with regular topn lists
            List<ObjectNode> candidates = universe.stream()
                    .filter(x -> !ratedMovieIds.contains(x.get("movieId").asInt()))
                    .collect(Collectors.toList());

            try {
                getUserVector(userId); // Make sure user has a vector
                List<Prediction> predictions = predictor.predict(candidates, requestContext);
                Ordering<Prediction> ordering = RankerUtilities.scoredResultScoreOrdering();
                List<ObjectNode> selected = ordering.greatestOf(predictions, 10)
                        .stream()
                        .map(p -> p.getEntity())
                        .collect(Collectors.toList());
                return response(selected, params);
            } catch (NoSuchElementException e) {
                params.put("algorithm", 3);

                Collections.shuffle(universe);
                List<ObjectNode> selected = universe.subList(0, Math.min(10, universe.size()));
                return response(selected, params);
            }

        } else if (expt.get("algorithm") == 2 || expt.get("algorithm") == 3) { // random algorithm
            // Randomly select 10 movies from the universe and return them ordered by popularity
            Collections.shuffle(universe);
            Ordering<ObjectNode> ordering = RetrieverUtilities.jsonFieldOrdering("support").reverse();
            List<ObjectNode> selected = ordering.immutableSortedCopy(universe.subList(0, Math.min(10, universe.size())));
            return response(selected, params);
        }

        // If none of the conditions matched, error out.
        throw new BadRequestException("algorithm must be 0, 1, 2, or 3");
    }



    /////////////////
    // SCORE ITEMS //
    /////////////////

    // Scores items *in place*, and then returns a filtered list containing
    // only the items that were successfully scored.
    private List<ObjectNode> scoreAndFilterItems(RealVector desiredVec, List<ObjectNode> universe) {
        List<ObjectNode> filteredItems = new ArrayList<>();
        for (ObjectNode entity : universe) {
            RealVector movieVec;
            try {
                movieVec = getMovieVector(entity.get("movieId").asInt());
            } catch (NoSuchElementException e) { // movie not in model!
                logger.warn("movie {} not in ephemeral model", entity.get("movieId").asInt());
                continue;
            }

            // Score the entity
            double cosine = getSimilarityMetricScore("cosine", desiredVec, movieVec);
            double dotProduct = getSimilarityMetricScore("dotProduct", desiredVec, movieVec);
            double magnitude = movieVec.getNorm();
            double score1 = dotProduct * Math.pow(Math.abs(cosine), 1);
            double score2 = dotProduct * Math.pow(Math.abs(cosine), 2);
            double score3 = dotProduct * Math.pow(Math.abs(cosine), 3);
            double score4 = dotProduct * Math.pow(Math.abs(cosine), 3);
            double score5 = dotProduct * Math.pow(Math.abs(cosine), 3);

            // Work on a copy of the entity, not the original.
            entity = entity.deepCopy();
            entity.put("cosine", cosine);
            entity.put("dotProduct", dotProduct);
            entity.put("magnitude", magnitude);
            entity.put("score1", score1);
            entity.put("score2", score2);
            entity.put("score3", score3);
            entity.put("score4", score4);
            entity.put("score5", score5);

            filteredItems.add(entity);
        }

//        for (String key : Arrays.asList("score1", "score2", "score3", "cosine", "dotProduct", "magnitude")) {
//            double min = filteredItems.stream().mapToDouble(x -> x.get(key).asDouble()).min().orElse(1.0 / 0.0);
//            double max = filteredItems.stream().mapToDouble(x -> x.get(key).asDouble()).max().orElse(-1.0 / 0.0);
//            logger.debug("{}: min{}, max {}", key, d.format(min), d.format(max));
//        }

        return filteredItems;
    }

    /*
     * Calculates the specified similarity metric for two vectors. Return
     * values are guaranteed to be nonnegative. Can be asked to use distance
     * metrics, in which case the method falls back to using 0.5^[distance].
     * This results in a similarity value between 0 and 1, like with cosine.
     */
    private double getSimilarityMetricScore(String metric, RealVector vec1, RealVector vec2) {
        switch (metric) {
            case "dotProduct": return vec1.dotProduct(vec2);
            case "cosine": return vec1.cosine(vec2);
            default: return Math.pow(0.5, getDistanceMetricScore(metric, vec1, vec2));

        }
    }

    /*
     * Calculates the specified distance metric for two vectors. Return values
     * are guaranteed to be nonnegative.
     */
    private double getDistanceMetricScore(String metric, RealVector vec1, RealVector vec2) {
        switch (metric) {
            case "euclideanCosine": return (0.5 - (vec1.cosine(vec2) / 2)) * vec1.getDistance(vec2);
            case "manhattanCosine": return (0.5 - (vec1.cosine(vec2) / 2)) * vec1.getL1Distance(vec2);
            case "cosine": return 0.5 - (vec1.cosine(vec2) / 2); // angular distance
            case "euclideanDistance": return vec1.getDistance(vec2);
            case "manhattanDistance": return vec1.getL1Distance(vec2);
            case "maxDistance": return vec1.getLInfDistance(vec2);
            case "minDistance": return vec1.subtract(vec2).map(x -> Math.abs(x)).getMinValue();
            default: throw new ConfigurationException("metric not regonized");
        }
    }

    //////////////////
    // SELECT ITEMS //
    //////////////////

    private void selectItems(List<ObjectNode> selected,
                             List<ObjectNode> candidates,
                             List<Integer> ratedMovieIds,
                             SelectionCriteria criteria) {

        int startingSize = selected.size();
        while (selected.size() < startingSize + criteria.n) {
            TopNAccumulator<ObjectNode> top = new TopNAccumulator<>(criteria.nthMostDistant);
            Map<Integer, RealVector> selectedMap = selected.stream()
                    .map(obj -> obj.get("movieId").asInt())
                    .collect(Collectors.toMap(x -> x, x -> getMovieVector(x)));

//            double minValue = - 1.0 / 0.0;
//            double maxValue = 1.0 / 0.0;
            int numIterated = 0;
            int numCompared = 0;
            for (ObjectNode obj : candidates) {
                int id = obj.get("movieId").asInt();
                if (selectedMap.containsKey(id)) { continue; }
                if (obj.get(criteria.similarityMetric).asDouble() < criteria.excludeBelow) { break; }

                numIterated++;
                if (random.nextDouble() < criteria.dropout) { continue; }
                if (ratedMovieIds.contains(id) && random.nextDouble() < criteria.ratedDropout) { continue; }

                double distance = getMinDistanceToVectors(criteria.diversityMetric, selectedMap.values(), getMovieVector(id));
                if (distance < 0) {
                    // Randomize first selected choice
                    distance = distance * random.nextDouble();
                }
                numCompared++;
                top.put(obj, distance);
//
//                if (numCompared == 1) {
//                    maxValue = obj.get(criteria.similarityMetric).asDouble();
//                }
//                if (numCompared >= criteria.limit) {
//                    minValue = obj.get(criteria.similarityMetric).asDouble();
//                    break;
//                }
            }

            try {
                ObjectNode chosen = top.min();
                selected.add(chosen);
            } catch (IndexOutOfBoundsException e) {
                logger.error("Unable to select most distant item {}. Compared {} of {} items from {} total candidates following selectionCriteria for round {}+", selected.size() + 1, numCompared, numIterated, candidates.size(), criteria.minRoundNumber);
                throw e;
            }

//            double distance = getMinDistanceToVectors(
//                    criteria.diversityMetric,
//                    selectedMap.values(),
//                    getMovieVector(chosen.get("movieId").asInt())
//            );
//            logger.debug("Selected item with {}={} after comparing {} of {} items with {}=[{}, {}]",
//                    criteria.diversityMetric, d.format(distance),
//                    numCompared, numIterated,
//                    criteria.similarityMetric, d.format(minValue), d.format(maxValue));
        }
    }

    private double getAvgDistanceToVectors(String metric, Collection<RealVector> vectors, RealVector target) {
        return vectors.stream()
                .mapToDouble(v -> getDistanceMetricScore(metric, v, target))
                .average()
                .orElse(-1.0);
    }

    private double getMinDistanceToVectors(String metric, Collection<RealVector> vectors, RealVector target) {
        return vectors.stream()
                .mapToDouble(v -> getDistanceMetricScore(metric, v, target))
                .min()
                .orElse(-1.0);
    }

    //////////////////////
    // HELPER FUNCTIONS //
    //////////////////////

    private String realVectorToString(RealVector vec) {
        String[] arr = new String[vec.getDimension()];
        for (int i=0; i<vec.getDimension(); i++) {
            arr[i] = d.format(Double.valueOf(vec.getEntry(i)));
        }
        return StringUtils.join(arr, ",");
    }

    private RankedResult response(List<ObjectNode> selected, ObjectNode params) {
        // Return the chosen set of movies in the correct format.
        List<Prediction> ranking = selected.stream()
                .map((entity) -> new Prediction(entity, null, 0.0))
                .collect(Collectors.toList());

        return new RankedResult(ranking, 0, ranking.size(), ranking.size(), params);
    }
}