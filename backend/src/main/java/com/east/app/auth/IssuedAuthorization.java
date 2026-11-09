package com.east.app.auth;

record IssuedAuthorization(String state, String nonce) {
    @Override
    public String toString() {
        return "IssuedAuthorization[state=REDACTED, nonce=REDACTED]";
    }
}
