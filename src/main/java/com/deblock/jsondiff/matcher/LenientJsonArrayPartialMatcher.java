package com.deblock.jsondiff.matcher;

import com.deblock.jsondiff.diff.JsonArrayDiff;
import com.deblock.jsondiff.diff.JsonDiff;
import com.deblock.jsondiff.model.IndexedJsonNode;
import com.deblock.jsondiff.model.Pair;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.*;
import java.util.stream.Collectors;

public class LenientJsonArrayPartialMatcher implements PartialJsonMatcher<ArrayNode> {
    @Override
    public JsonDiff jsonDiff(Path path, ArrayNode expectedArrayNode, ArrayNode recievedArrayNode, JsonMatcher jsonMatcher) {
        final var diff = new JsonArrayDiff(path);
        var mismatches = handleEqualJsonArrayNodeElements(expectedArrayNode, recievedArrayNode, diff, path, jsonMatcher);
        if (!mismatches.getExpectedMissing().isEmpty() || !mismatches.getActualMissing().isEmpty()) {
            final var diffMap = new HashMap<Integer, Map<Integer, JsonDiff>>();
            for (int i = 0; i < mismatches.getExpectedMissing().size(); i++) {
                final var map = new HashMap<Integer, JsonDiff>();
                for (var x = 0; x < mismatches.getActualMissing().size(); ++x) {
                    map.put(mismatches.getActualMissing().get(x).getIndex(), jsonMatcher.diff(path.add(Path.PathItem.of(mismatches.getExpectedMissing().get(i).getIndex())), mismatches.getExpectedMissing().get(i).getJsonNode(), mismatches.getActualMissing().get(x).getJsonNode()));
                }
                diffMap.put(mismatches.getExpectedMissing().get(i).getIndex(), map);
            }

            final var entrySortedByBestMatch = diffMap.entrySet().stream().sorted(Comparator.comparingDouble(this::maxSimilarityRate).reversed()).toList();
            final var alreadyMatchedIndex = new HashSet<Integer>();

            for (final var entry : entrySortedByBestMatch) {
                final var matchedItem = entry.getValue().entrySet().stream().filter(e -> !alreadyMatchedIndex.contains(e.getKey())).max(Comparator.comparingDouble(e -> e.getValue().similarityRate()));

                if (matchedItem.isEmpty()) {
                    diff.addNoMatch(entry.getKey(), expectedArrayNode.get(entry.getKey()));
                } else {
                    diff.addDiff(entry.getKey(), matchedItem.get().getValue());
                    alreadyMatchedIndex.add(matchedItem.get().getKey());
                }
            }

            if (alreadyMatchedIndex.size() < recievedArrayNode.size()) {
                final var receivedIndex = mismatches.getActualMissing().stream().map(IndexedJsonNode::getIndex).collect(Collectors.toList());
                receivedIndex.removeAll(alreadyMatchedIndex);
                receivedIndex.forEach(index -> diff.addExtraItem(index, recievedArrayNode.get(index)));
            }
        }

        return diff;
    }

    private double maxSimilarityRate(Map.Entry<Integer, Map<Integer, JsonDiff>> entry) {
        return entry.getValue().values().stream().mapToDouble(JsonDiff::similarityRate).max().orElse(0);
    }

    private Pair<List<IndexedJsonNode>, List<IndexedJsonNode>> handleEqualJsonArrayNodeElements(ArrayNode expectedArrayNode, ArrayNode actualArrayNode, JsonArrayDiff diff, Path path, JsonMatcher jsonMatcher) {
        List<IndexedJsonNode> expectedMissing = new ArrayList<>();
        List<IndexedJsonNode> actualMissing = new ArrayList<>();

        if (actualArrayNode.equals(expectedArrayNode)) {
            for (int i = 0; i < expectedArrayNode.size(); i++) {
                diff.addDiff(i, jsonMatcher.diff(path.add(Path.PathItem.of(i)), expectedArrayNode.get(i), expectedArrayNode.get(i)));
            }
            return new Pair<>(expectedMissing, actualMissing);
        }
        Map<JsonNode, Integer> expectedNodeCounter = getElementsWithCount(expectedArrayNode.elements());
        Map<JsonNode, Integer> actualNodeCounter = getElementsWithCount(actualArrayNode.elements());

        for (int i = 0; i < expectedArrayNode.size(); i++) {
            var expectedElement = expectedArrayNode.get(i);
            if (!actualNodeCounter.containsKey(expectedElement)) {
                expectedMissing.add(new IndexedJsonNode(i, expectedArrayNode.get(i)));
            } else {
                actualNodeCounter.put(expectedElement, actualNodeCounter.get(expectedElement) - 1);
                if (actualNodeCounter.get(expectedElement) == 0) {
                    actualNodeCounter.remove(expectedElement);
                }
                diff.addDiff(i, jsonMatcher.diff(path.add(Path.PathItem.of(i)), expectedElement, expectedElement));
            }
        }
        for (int i = 0; i < actualArrayNode.size(); i++) {
            var actualElement = actualArrayNode.get(i);
            if (!expectedNodeCounter.containsKey(actualElement)) {
                actualMissing.add(new IndexedJsonNode(i, actualArrayNode.get(i)));
            } else {
                expectedNodeCounter.put(actualElement, expectedNodeCounter.get(actualElement) - 1);
                if (expectedNodeCounter.get(actualElement) == 0) {
                    expectedNodeCounter.remove(actualElement);
                }
            }
        }
        return new Pair<>(expectedMissing, actualMissing);
    }

    private <T> Map<T, Integer> getElementsWithCount(Iterator<T> elements) {
        Map<T, Integer> nodeCounter = new HashMap<>();
        elements.forEachRemaining(
                element -> {
                    if (nodeCounter.containsKey(element)) {
                        nodeCounter.put(element, nodeCounter.getOrDefault(element, 0) + 1);
                    }
                }
        );
        return nodeCounter;
    }
}
