package com.octopyder.payroll.common;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PageResponseTest {

    @Test
    void pageResponseComputesTotalPagesCorrectly() {
        PageResponse<String> response = PageResponse.of(List.of("a", "b", "c"), 0, 10, 25);

        assertThat(response.items()).containsExactly("a", "b", "c");
        assertThat(response.page()).isEqualTo(0);
        assertThat(response.size()).isEqualTo(10);
        assertThat(response.totalItems()).isEqualTo(25);
        assertThat(response.totalPages()).isEqualTo(3);
    }

    @Test
    void pageResponseHandlesEmptyPage() {
        PageResponse<String> response = PageResponse.of(List.of(), 0, 10, 0);

        assertThat(response.items()).isEmpty();
        assertThat(response.totalPages()).isEqualTo(0);
    }
}
