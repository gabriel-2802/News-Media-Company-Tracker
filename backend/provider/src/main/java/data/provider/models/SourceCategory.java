package data.provider.models;

/**
 * What kind of publisher a {@link NewsSource} is. Used to filter events
 * (e.g. "regulator actions only") and to tune scrape frequency.
 */
public enum SourceCategory {
    /** General financial / business news outlet. */
    NEWS,
    /** Banking, payments or fintech trade publication. */
    TRADE_PRESS,
    /** Supervisory or enforcement authority (FDIC, OCC, CFPB, SEC, FCA, EBA, ...). */
    REGULATOR,
    /** Central bank (Federal Reserve, ECB, Bank of England, ...). */
    CENTRAL_BANK,
    /** Regulatory filings feed (e.g. SEC EDGAR 8-K). */
    FILING
}
