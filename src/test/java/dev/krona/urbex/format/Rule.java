package dev.krona.urbex.format;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds a test method to the specification rule(s) it proves.
 * <p>
 * This is the only binding between a rule identifier and the test that exercises it -
 * {@code docs/format/README.md} §5.1 deliberately defines no naming convention to infer the link
 * from, because a naming convention breaks the moment a test is renamed or a rule is split. A
 * citation survives both: {@link ConformanceIndexTest} finds it by scanning source text for this
 * annotation, not by pattern-matching the method name against a rule id.
 * <p>
 * Repeatable because a single test commonly proves more than one rule at once - an {@code accept}
 * fixture and its paired {@code reject} sibling are often exercised by the same parameterised test.
 * <p>
 * The name collides with nothing in this project: it is JUnit 5, which never defined a {@code @Rule}
 * (that was JUnit 4's mechanism, for a different purpose entirely), so the identifier this
 * specification already uses in prose - {@code docs/format/README.md} §5.1 - is free to reuse
 * verbatim rather than invent a synonym.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Repeatable(Rule.Rules.class)
public @interface Rule {

    /** A rule identifier such as {@code "REF.032"}, as assigned in {@code docs/format/}. */
    String value();

    /**
     * The container the compiler synthesises for a repeated {@link Rule}. Never written by hand -
     * write {@code @Rule("A.001") @Rule("A.002")}, not this.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface Rules {
        Rule[] value();
    }
}
