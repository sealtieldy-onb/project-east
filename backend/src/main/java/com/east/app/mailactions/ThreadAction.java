package com.east.app.mailactions;

/** Explicit user-requested mailbox changes supported by the thread action routes. */
public enum ThreadAction {
    ARCHIVE,
    TRASH,
    UNTRASH,
    MARK_READ,
    MARK_UNREAD,
    SPAM,
    NOT_SPAM,
    STAR,
    UNSTAR,
    ADD_LABELS,
    REMOVE_LABELS
}
