package com.telecom.support.common.web;

/** Normalised paging input: 0-based page, size clamped to [1, 100]. */
public record Paging(int page, int size) {

    public static final int MAX_SIZE = 100;

    public static Paging of(Integer page, Integer size) {
        int p = page == null || page < 0 ? 0 : page;
        int s = size == null || size < 1 ? 20 : Math.min(size, MAX_SIZE);
        return new Paging(p, s);
    }

    public int offset() {
        return page * size;
    }
}
