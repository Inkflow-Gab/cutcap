package com.getcapacitor.annotation;

/** Stub. See ../../README.md. Signatures match Capacitor 8. */
public @interface Permission {
    String[] strings() default {};

    String alias() default "";
}
