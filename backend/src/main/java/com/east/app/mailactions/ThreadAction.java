package com.east.app.mailactions;

/** Explicit user-requested mailbox changes supported by the thread action routes. */
public enum ThreadAction {
    ARCHIVE,
    MOVE_TO_INBOX,
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
