package com.example.logviewer.logs.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SearchScopeTest {

    @Test
    void shouldParseRecentDayAliases() {
        assertThat(SearchScope.from("recent3d")).isEqualTo(SearchScope.LAST_3_DAYS);
        assertThat(SearchScope.from("last_7_days")).isEqualTo(SearchScope.LAST_7_DAYS);
        assertThat(SearchScope.from("date_range")).isEqualTo(SearchScope.DATE_RANGE);
    }

    @Test
    void shouldFallbackToCurrentFileWhenValueMissing() {
        assertThat(SearchScope.from(null)).isEqualTo(SearchScope.CURRENT_FILE);
    }
}
