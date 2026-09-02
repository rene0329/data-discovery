package org.example.vo;

import java.util.Collections;
import java.util.List;

public class PageResult<T> {
    private List<T> list;
    private long total;
    private int pageNum;
    private int pageSize;

    public PageResult() {}

    public PageResult(List<T> list, long total, int pageNum, int pageSize) {
        this.list = list;
        this.total = total;
        this.pageNum = pageNum;
        this.pageSize = pageSize;
    }

    public static <T> PageResult<T> of(List<T> fullList, int pageNum, int pageSize) {
        if (pageNum < 1) {
            throw new IllegalArgumentException("page must be greater than or equal to 1");
        }
        if (pageSize < 1 || pageSize > 100) {
            throw new IllegalArgumentException("pageSize must be between 1 and 100");
        }
        if (fullList == null || fullList.isEmpty()) {
            return new PageResult<>(Collections.emptyList(), 0, pageNum, pageSize);
        }
        int totalItems = fullList.size();
        long offset = (long) (pageNum - 1) * pageSize;
        if (offset >= totalItems) {
            return new PageResult<>(Collections.emptyList(), totalItems, pageNum, pageSize);
        }
        int fromIndex = (int) offset;
        int toIndex = (int) Math.min(offset + pageSize, totalItems);
        List<T> sub = fullList.subList(fromIndex, toIndex);
        return new PageResult<>(sub, totalItems, pageNum, pageSize);
    }

    public List<T> getList() {
        return list;
    }

    public void setList(List<T> list) {
        this.list = list;
    }

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
    }

    public int getPageNum() {
        return pageNum;
    }

    public void setPageNum(int pageNum) {
        this.pageNum = pageNum;
    }

    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }
}
