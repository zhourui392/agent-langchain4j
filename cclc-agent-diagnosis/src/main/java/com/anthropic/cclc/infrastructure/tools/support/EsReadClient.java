package com.anthropic.cclc.infrastructure.tools.support;

import java.io.IOException;

/**
 * Read-only Elasticsearch operations, kept as a seam so {@code EsReadTool} can be
 * unit-tested without a live cluster. The default {@link HttpEsReadClient} talks
 * to the ES REST API over plain HTTP — no heavyweight ES client dependency.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-08
 */
public interface EsReadClient {

    String search(String index, String queryJson, int size) throws IOException;

    long count(String index, String queryJson) throws IOException;

    String get(String index, String id) throws IOException;

    String mapping(String index) throws IOException;
}
