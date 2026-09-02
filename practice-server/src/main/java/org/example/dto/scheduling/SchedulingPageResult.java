package org.example.dto.scheduling;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class SchedulingPageResult<T> {
    private List<T> list;
    private long total;
    private int page;
    private int pageSize;
}
