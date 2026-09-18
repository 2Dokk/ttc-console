package com.gsc.command;

/**
 * Command lifecycle. Delivery (ACKED) and execution (EXECUTED/REJECTED) are verified separately:
 * a frame can reach the spacecraft and still be refused by it.
 *
 * <pre>
 * PENDING --tx--> SENT --CLCW--> ACKED --exec report--> EXECUTED | REJECTED
 *    |              '--timeout: go-back-N retransmit (stays SENT)
 *    '--> EXPIRED (not transmitted before its deadline) | CANCELLED (by operator)
 * SENT/ACKED --ground+spacecraft restart--> FAILED
 * </pre>
 */
public enum CommandStatus {
    PENDING,
    SENT,
    ACKED,
    EXECUTED,
    REJECTED,
    EXPIRED,
    CANCELLED,
    FAILED
}
