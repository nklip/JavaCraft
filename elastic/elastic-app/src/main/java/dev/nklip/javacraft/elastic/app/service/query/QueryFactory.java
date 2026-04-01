package dev.nklip.javacraft.elastic.app.service.query;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;

public interface QueryFactory {

    Query createQuery (String field, String value);

}
