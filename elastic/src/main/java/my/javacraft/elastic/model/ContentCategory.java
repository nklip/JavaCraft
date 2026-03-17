package my.javacraft.elastic.model;

public enum ContentCategory {

    ALL,
    BOOKS,
    COMPANIES,
    MUSIC,
    MOVIES,
    PEOPLE;

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
