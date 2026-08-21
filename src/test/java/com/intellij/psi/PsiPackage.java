package com.intellij.psi;

/**
 * IU 262 no longer ships the Java PSI package abstraction that the bundled Cursive build loads
 * while installing its lookup protocol. Completion tests do not create Java package lookup rows;
 * they only need Cursive's namespace initializer to retain a class key for that protocol branch.
 */
public interface PsiPackage {
}
