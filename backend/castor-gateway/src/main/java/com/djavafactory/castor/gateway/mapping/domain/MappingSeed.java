package com.djavafactory.castor.gateway.mapping.domain;

/**
 * One line of a seed file in {@code seed/identity-mappings}: what the source calls the thing, and either the code of an
 * asset in the published layout ({@code assetCode}, resolved to its id) or an id that is not an asset
 * ({@code canonicalId}, such as a vessel call). Exactly one of the two is set.
 */
public record MappingSeed(
        String sourceSystem, String sourceEntityType, String sourceId, String assetCode, String canonicalId) {

    public boolean wellFormed() {
        return present(sourceSystem)
                && present(sourceEntityType)
                && present(sourceId)
                && present(assetCode) != present(canonicalId);
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
