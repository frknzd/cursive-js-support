/** Fixture interface for offset verification. */
interface DomDocLike {
    /** Creates a range for the fixture. */
    createRange(): DomRangeLike;
}

interface DomRangeLike {}

declare const fixtureDoc: DomDocLike;
