package com.example.logviewer.ws;

import com.example.logviewer.logs.infrastructure.TailFilterOptions;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LogFollowServiceTest {

    @Test
    void shouldPassThroughLinesWithoutRealtimeFilter() {
        LogFollowService.RealtimeLineFilter filter = new LogFollowService.RealtimeLineFilter(TailFilterOptions.none());

        assertEquals(Arrays.asList("a"), filter.accept("a"));
        assertEquals(Arrays.asList("b"), filter.accept("b"));
    }

    @Test
    void shouldEmitRealtimeMatchesWithContextWithoutDuplicates() {
        LogFollowService.RealtimeLineFilter filter = new LogFollowService.RealtimeLineFilter(new TailFilterOptions("ERROR", false, 1));

        assertEquals(Arrays.asList(), filter.accept("before"));
        assertEquals(Arrays.asList("before", "ERROR first"), filter.accept("ERROR first"));
        assertEquals(Arrays.asList("after"), filter.accept("after"));
        assertEquals(Arrays.asList(), filter.accept("noise"));

        List<String> secondMatch = filter.accept("ERROR second");
        assertEquals(Arrays.asList("noise", "ERROR second"), secondMatch);
        assertEquals(Arrays.asList("tail"), filter.accept("tail"));
    }
}
