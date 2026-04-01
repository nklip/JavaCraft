package dev.nklip.javacraft.elastic.api.model;

public enum ContentCategory {

    ALL,
    BOOKS,
    COMPANIES,
    MUSIC,
    MOVIES,
    PEOPLE;

    /*
     * Current behavior intentionally defaults to ALL for unknown values.
     */
    public static ContentCategory valueByName(String name) {
        ContentCategory[] values = values();
        for (ContentCategory contentCategory : values) {
            if (contentCategory.name().equalsIgnoreCase(name)) {
                return contentCategory;
            }
        }
        return ALL;
    }

}
