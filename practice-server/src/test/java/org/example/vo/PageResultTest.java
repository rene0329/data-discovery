package org.example.vo;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PageResultTest {
    @Test
    void rejectsInvalidPageBounds() {
        assertThrows(IllegalArgumentException.class,
                () -> PageResult.of(Arrays.asList(1, 2), 0, 20));
        assertThrows(IllegalArgumentException.class,
                () -> PageResult.of(Arrays.asList(1, 2), 1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> PageResult.of(Arrays.asList(1, 2), 1, 101));
    }

    @Test
    void returnsRequestedPage() {
        PageResult<Integer> result = PageResult.of(Arrays.asList(1, 2, 3), 2, 1);
        assertEquals(Arrays.asList(2), result.getList());
        assertEquals(3, result.getTotal());
    }
}
