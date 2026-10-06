package io.nightbeam.donutauction.model;

import java.util.List;
import java.util.UUID;

public record TransactionPage(UUID viewer, HistoryView view, List<TransactionRecord> records,
                              int currentPage, int totalPages, long totalResults) {
    public TransactionPage { records = List.copyOf(records); }
}
