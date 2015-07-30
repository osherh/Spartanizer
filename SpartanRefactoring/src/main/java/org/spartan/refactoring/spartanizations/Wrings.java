package org.spartan.refactoring.spartanizations;

import org.spartan.refactoring.utils.Extract;
import static org.eclipse.jdt.core.dom.InfixExpression.Operator.AND;
import static org.eclipse.jdt.core.dom.InfixExpression.Operator.CONDITIONAL_AND;
import static org.eclipse.jdt.core.dom.InfixExpression.Operator.CONDITIONAL_OR;
import static org.eclipse.jdt.core.dom.InfixExpression.Operator.EQUALS;
import static org.eclipse.jdt.core.dom.InfixExpression.Operator.NOT_EQUALS;
import static org.eclipse.jdt.core.dom.InfixExpression.Operator.OR;
import static org.eclipse.jdt.core.dom.InfixExpression.Operator.PLUS;
import static org.eclipse.jdt.core.dom.InfixExpression.Operator.TIMES;
import static org.eclipse.jdt.core.dom.InfixExpression.Operator.XOR;
import static org.eclipse.jdt.core.dom.PrefixExpression.Operator.NOT;
import static org.spartan.refactoring.utils.Funcs.asAndOrOr;
import static org.spartan.refactoring.utils.Funcs.*;
import static org.spartan.refactoring.utils.Restructure.conjugate;
import static org.spartan.refactoring.utils.Restructure.flatten;
import static org.spartan.refactoring.utils.Restructure.getCore;
import static org.spartan.refactoring.utils.Restructure.refitOperands;
import static org.spartan.utils.Utils.in;

import java.util.List;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.BooleanLiteral;
import org.eclipse.jdt.core.dom.ConditionalExpression;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.IfStatement;
import org.eclipse.jdt.core.dom.InfixExpression;
import org.eclipse.jdt.core.dom.InfixExpression.Operator;
import org.eclipse.jdt.core.dom.ParenthesizedExpression;
import org.eclipse.jdt.core.dom.PrefixExpression;
import org.eclipse.jdt.core.dom.Statement;
import org.spartan.refactoring.utils.All;
import org.spartan.refactoring.utils.Are;
import org.spartan.refactoring.utils.Have;
import org.spartan.refactoring.utils.Is;

/**
 * This enum represents an ordered list of all {@link Wring} objects.
 *
 * @author Yossi Gil
 * @since 2015-07-17
 */
public enum Wrings {
  /**
   * A {@link Wring} to convert
   *
   * <pre>
   * if (x)
   *   return b;
   * else
   *   return c;
   * </pre>
   *
   * into
   *
   * <pre>
   * return x x? b : c
   * </pre>
   *
   * @author Yossi Gil
   * @since 2015-07-29
   */
  IF_RETURN_A_ELSE_RETURN_B(new Wring.OfStatement() {
    @Override boolean _eligible(@SuppressWarnings("unused") final Statement _) {
      return true;
    }
    @Override Statement _replacement(final Statement e) {
      // TODO Auto-generated method stub
      return null;
    }
    @Override boolean scopeIncludes(final Statement e) {
      final IfStatement i = asIfStatement(e);
      if (i == null)
        return false;
      final Expression then = Extract.returnExpression(i.getThenStatement());
      final Expression elze = Extract.returnExpression(i.getElseStatement());
      return then != null && elze != null;
    }
  }), //
  /**
   * A {@link Wring} that eliminates redundant comparison with the two boolean
   * literals: <code><b>true</b></code> and <code><b>false</b></code>.
   *
   * @author Yossi Gil
   * @since 2015-07-17
   */
  COMPARISON_WITH_BOOLEAN(new Wring.OfInfixExpression() {
    @Override public final boolean scopeIncludes(final InfixExpression e) {
      return in(e.getOperator(), EQUALS, NOT_EQUALS) && (Is.booleanLiteral(e.getRightOperand()) || Is.booleanLiteral(e.getLeftOperand()));
    }
    private PrefixExpression negate(final ASTNode e) {
      return makePrefixExpression(makeParenthesizedExpression((Expression) e), NOT);
    }
    private boolean nonNegating(final InfixExpression e, final BooleanLiteral literal) {
      return literal.booleanValue() == (e.getOperator() == EQUALS);
    }
    @Override boolean _eligible(final InfixExpression e) {
      assert scopeIncludes(e);
      return true;
    }
    @Override Expression _replacement(final InfixExpression e) {
      Expression nonliteral;
      BooleanLiteral literal;
      if (Is.booleanLiteral(e.getLeftOperand())) {
        literal = asBooleanLiteral(e.getLeftOperand());
        nonliteral = duplicate(e.getRightOperand());
      } else {
        literal = asBooleanLiteral(e.getRightOperand());
        nonliteral = duplicate(e.getLeftOperand());
      }
      return nonNegating(e, literal) ? nonliteral : negate(nonliteral);
    }
  }), //
  /**
   * A {@link Wring} that reorder comparisons so that the specific value is
   * placed on the right. Specific value means a literal, or any of the two
   * keywords <code><b>this</b></code> or <code><b>null</b></code>.
   *
   * @author Yossi Gil
   * @since 2015-07-17
   */
  COMPARISON_WITH_SPECIFIC(new Wring.OfInfixExpression() {
    @Override public boolean scopeIncludes(final InfixExpression e) {
      return Is.comparison(e) && (hasThisOrNull(e) || hasOneSpecificArgument(e));
    }
    @Override public String toString() {
      return "Comparison WITH SPECIFIC";
    }
    private boolean hasOneSpecificArgument(final InfixExpression e) {
      // One of the arguments must be specific, the other must not be.
      return Is.specific(e.getLeftOperand()) != Is.specific(e.getRightOperand());
    }
    @Override boolean _eligible(final InfixExpression e) {
      return Is.specific(e.getLeftOperand());
    }
    @Override Expression _replacement(final InfixExpression e) {
      return flip(e);
    }
    boolean hasThisOrNull(final InfixExpression e) {
      return Is.thisOrNull(e.getLeftOperand()) || Is.thisOrNull(e.getRightOperand());
    }
  }), //
  /**
   * <code>
   * a ? b : c
   * </code> is the same as <code>
   * (a && b) || (!a && c)
   * </code> if b is false than: <code>
   * (a && false) || (!a && c) == (!a && c)
   * </code> if b is true than: <code>
   * (a && true) || (!a && c) == a || (!a && c) == a || c
   * </code> if c is false than: <code>
   * (a && b) || (!a && false) == (!a && c)
   * </code> if c is true than <code>
   * (a && b) || (!a && true) == (a && b) || (!a) == !a || b
   * </code> keywords <code><b>this</b></code> or <code><b>null</b></code>.
   *
   * @author Yossi Gil
   * @since 2015-07-20
   */
  TERNARY_BOOLEAN_LITERAL(new Wring.OfConditionalExpression() {
    @Override public String toString() {
      return "TERNARY_BOOLEAN_LITERAL";
    }
    @Override boolean _eligible(@SuppressWarnings("unused") final ConditionalExpression _) {
      return true;
    }
    @Override Expression _replacement(final ConditionalExpression e) {
      return simplifyTernary(e);
    }
    @Override boolean scopeIncludes(final ConditionalExpression e) {
      return isTernaryOfBooleanLitreral(e);
    }
  }), //
  /**
   * A {@link Wring} that eliminate Boolean literals, when possible present on
   * logical AND an logical OR.
   *
   * @author Yossi Gil
   * @since 2015-07-20
   */
  AND_TRUE(new Wring.OfInfixExpression() {
    @Override public String toString() {
      return "&& true";
    }
    @Override boolean _eligible(@SuppressWarnings("unused") final InfixExpression _) {
      return true;
    }
    @Override Expression _replacement(final InfixExpression e) {
      return eliminateLiteral(e, true);
    }
    @Override boolean scopeIncludes(final InfixExpression e) {
      return Is.conditionalAnd(e) && Have.trueLiteral(All.operands(flatten(e)));
    }
  }), //
  /**
   * A {@link Wring} that eliminate Boolean literals, when possible present on
   * logical AND an logical OR.
   *
   * @author Yossi Gil
   * @since 2015-07-20
   */
  OR_FALSE(new Wring.OfInfixExpression() {
    @Override public String toString() {
      return "|| true";
    }
    @Override boolean _eligible(@SuppressWarnings("unused") final InfixExpression _) {
      return true;
    }
    @Override Expression _replacement(final InfixExpression e) {
      return eliminateLiteral(e, false);
    }
    @Override boolean scopeIncludes(final InfixExpression e) {
      return Is.conditionalOr(e) && Have.falseLiteral(All.operands(flatten(e)));
    }
  }), //
  /**
   * A {@link Wring} that sorts the arguments of a {@link Operator#PLUS}
   * expression. Extra care is taken to leave intact the use of
   * {@link Operator#PLUS} for the concatenation of {@link String}s.
   *
   * @author Yossi Gil
   * @since 2015-07-17
   */
  ADDITION_SORTER(new Wring.OfInfixExpression() {
    @Override public String toString() {
      return "Addition sorter";
    }
    private boolean tryToSort(final InfixExpression e) {
      return tryToSort(All.operands(flatten(e)));
    }
    private boolean tryToSort(final List<Expression> es) {
      return Wrings.tryToSort(es, ExpressionComparator.ADDITION);
    }
    @Override boolean _eligible(final InfixExpression e) {
      return Are.notString(All.operands(flatten(e))) && tryToSort(e);
    }
    @Override Expression _replacement(final InfixExpression e) {
      final List<Expression> operands = All.operands(flatten(e));
      return !Are.notString(operands) || !tryToSort(operands) ? null : refitOperands(e, operands);
    }
    @Override boolean scopeIncludes(final InfixExpression e) {
      return e.getOperator() == PLUS;
    }
  }), //
  /**
   * A {@link Wring} that sorts the arguments of an expression using the same
   * sorting order as {@link Operator#PLUS} expression, except that we do not
   * worry about commutativity. Unlike {@link #ADDITION_SORTER}, we know that
   * the reordering is always possible.
   *
   * @see #ADDITION_SORTER
   * @author Yossi Gil
   * @since 2015-07-17
   */
  PSEUDO_ADDITION_SORTER(new Wring.OfInfixExpression() {
    @Override public String toString() {
      return "pseudo addition sorter";
    }
    private boolean tryToSort(final InfixExpression e) {
      return tryToSort(All.operands(flatten(e)));
    }
    private boolean tryToSort(final List<Expression> es) {
      return Wrings.tryToSort(es, ExpressionComparator.ADDITION);
    }
    @Override boolean _eligible(final InfixExpression e) {
      return tryToSort(e);
    }
    @Override Expression _replacement(final InfixExpression e) {
      final List<Expression> operands = All.operands(flatten(e));
      return !tryToSort(operands) ? null : refitOperands(e, operands);
    }
    @Override boolean scopeIncludes(final InfixExpression e) {
      return in(e.getOperator(), OR);
    }
  }), //
  /**
   * A {@link Wring} that sorts the arguments of a {@link Operator#PLUS}
   * expression. Extra care is taken to leave intact the use of
   * {@link Operator#PLUS} for the concatenation of {@link String}s.
   *
   * @author Yossi Gil
   * @since 2015-07-17
   */
  MULTIPLICATION_SORTER(new Wring.OfInfixExpression() {
    @Override public String toString() {
      return "Multiplication sorter";
    }
    private boolean tryToSort(final InfixExpression e) {
      return tryToSort(All.operands(flatten(e)));
    }
    private boolean tryToSort(final List<Expression> es) {
      return Wrings.tryToSort(es, ExpressionComparator.MULTIPLICATION);
    }
    @Override boolean _eligible(final InfixExpression e) {
      return tryToSort(e);
    }
    @Override Expression _replacement(final InfixExpression e) {
      final List<Expression> operands = All.operands(flatten(e));
      return !tryToSort(operands) ? null : refitOperands(e, operands);
    }
    @Override boolean scopeIncludes(final InfixExpression e) {
      return in(e.getOperator(), TIMES, XOR, AND);
    }
  }), //
  /**
   * A {@link Wring} that pushes down "<code>!</code>", the negation operator as
   * much as possible, using the de-Morgan and other simplification rules.
   *
   * @author Yossi Gil
   * @since 2015-7-17
   */
  PUSHDOWN_NOT(new Wring.OfPrefixExpression() {
    @Override public boolean scopeIncludes(final PrefixExpression e) {
      return e != null && asNot(e) != null && hasOpportunity(asNot(e));
    }
    @Override public String toString() {
      return "Pushdown not";
    }
    @Override boolean _eligible(final PrefixExpression e) {
      return true;
    }
    @Override Expression _replacement(final PrefixExpression e) {
      return pushdownNot(asNot(e));
    }
  }),
  //
  ;
  /**
   * Find the first {@link Wring} appropriate for an
   * {@link ConditionalExpression}
   *
   * @param e JD
   * @return the first {@link Wring} for which the parameter is eligible, or
   *         <code><b>null</b></code>i if no such {@link Wring} is found.
   */
  public static Wring find(final ConditionalExpression e) {
    if (e == null)
      return null;
    for (final Wrings s : values())
      if (s.inner.scopeIncludes(e))
        return s.inner;
    return null;
  }
  /**
   * Find the first {@link Wring} appropriate for an {@link Expression}
   *
   * @param e JD
   * @return the first {@link Wring} for which the parameter is eligible, or
   *         <code><b>null</b></code>i if no such {@link Wring} is found.
   */
  public static Wring find(final Expression e) {
    Wring $;
    return ($ = find(asInfixExpression(e))) != null//
        || ($ = find(asPrefixExpression(e))) != null//
        || ($ = find(asConditionalExpression(e))) != null//
            //
            ? $ : null;
  }
  /**
   * Find the first {@link Wring} appropriate for an {@link IfStatement}
   *
   * @param e JD
   * @return the first {@link Wring} for which the parameter is eligible, or
   *         <code><b>null</b></code>i if no such {@link Wring} is found.
   */
  public static Wring find(final Statement i) {
    if (i == null)
      return null;
    for (final Wrings w : values())
      if (w.inner.scopeIncludes(i))
        return w.inner;
    return null;
  }
  /**
   * Find the first {@link Wring} appropriate for an {@link InfixExpression}
   *
   * @param e JD
   * @return the first {@link Wring} for which the parameter is eligible, or
   *         <code><b>null</b></code>i if no such {@link Wring} is found.
   */
  public static Wring find(final InfixExpression e) {
    if (e == null)
      return null;
    for (final Wrings s : values())
      if (s.inner.scopeIncludes(e))
        return s.inner;
    return null;
  }
  /**
   * Find the first {@link Wring} appropriate for a {@link PrefixExpression}
   *
   * @param e JD
   * @return the first {@link Wring} for which the parameter is eligible, or
   *         <code><b>null</b></code>i if no such {@link Wring} is found.
   */
  public static Wring find(final PrefixExpression e) {
    if (e == null)
      return null;
    for (final Wrings s : Wrings.values())
      if (s.inner.scopeIncludes(e))
        return s.inner;
    return null;
  }
  private static InfixExpression makeWithOther(final Expression other) {
    final InfixExpression $ = other.getAST().newInfixExpression();
    $.setRightOperand(duplicate(other));
    return $;
  }
  private static Expression simplifyTernary(final Expression then, final Expression elze, final Expression main) {
    final boolean takeThen = !Is.booleanLiteral(then);
    final InfixExpression $ = makeWithOther(takeThen ? then : elze);
    final boolean literal = asBooleanLiteral(takeThen ? elze : then).booleanValue();
    $.setOperator(literal ? CONDITIONAL_OR : CONDITIONAL_AND);
    $.setLeftOperand(takeThen != literal ? main : not(main));
    return $;
  }
  static InfixExpression addExtendedOperands(final InfixExpression from, final InfixExpression $) {
    if (from.hasExtendedOperands())
      addExtendedOperands(from.extendedOperands(), $.extendedOperands());
    return $;
  }
  static void addExtendedOperands(final List<Expression> from, final List<Expression> to) {
    for (final Expression e : from)
      to.add(not(e));
  }
  static InfixExpression cloneInfixChangingOperator(final InfixExpression e, final Operator o) {
    return e == null ? null : makeInfixExpression(getCoreLeft(e), o, getCoreRight(e));
  }
  static Expression comparison(final InfixExpression inner) {
    return cloneInfixChangingOperator(inner, ShortestBranchFirst.negate(inner.getOperator()));
  }
  static Expression deMorgan(final InfixExpression inner, final Expression left, final Expression right) {
    return deMorgan1(inner, parenthesize(left), parenthesize(right));
  }
  static Expression deMorgan1(final InfixExpression inner, final Expression left, final Expression right) {
    return parenthesize( //
        addExtendedOperands(inner, //
            makeInfixExpression(not(left), conjugate(inner), not(right))));
  }
  static Expression eliminateLiteral(final InfixExpression e, final boolean b) {
    final List<Expression> operands = All.operands(flatten(e));
    removeAll(b, operands);
    switch (operands.size()) {
      case 0:
        return e.getAST().newBooleanLiteral(b);
      case 1:
        return duplicate(operands.get(0));
      default:
        return refitOperands(e, operands);
    }
  }
  static Expression getCoreLeft(final InfixExpression e) {
    return getCore(e.getLeftOperand());
  }
  static Expression getCoreRight(final InfixExpression e) {
    return getCore(e.getRightOperand());
  }
  static boolean hasOpportunity(final Expression inner) {
    return Is.booleanLiteral(inner) || asNot(inner) != null || asAndOrOr(inner) != null || asComparison(inner) != null;
  }
  static boolean hasOpportunity(final PrefixExpression e) {
    return e != null && hasOpportunity(getCore(e.getOperand()));
  }
  static boolean haveTernaryOfBooleanLitreral(final List<Expression> es) {
    for (final Expression e : es)
      if (isTernaryOfBooleanLitreral(e))
        return true;
    return false;
  }
  static boolean isTernaryOfBooleanLitreral(final ConditionalExpression e) {
    return e != null && Have.booleanLiteral(getCore(e.getThenExpression()), getCore(e.getElseExpression()));
  }
  static boolean isTernaryOfBooleanLitreral(final Expression e) {
    return isTernaryOfBooleanLitreral(asConditionalExpression(getCore(e)));
  }
  static InfixExpression makeInfixExpression(final Expression left, final Operator o, final Expression right) {
    final InfixExpression $ = left.getAST().newInfixExpression();
    $.setLeftOperand(duplicate(left));
    $.setOperator(o);
    $.setRightOperand(duplicate(right));
    return $;
  }
  static PrefixExpression not(final Expression e) {
    final PrefixExpression $ = e.getAST().newPrefixExpression();
    $.setOperator(NOT);
    $.setOperand(parenthesize(e));
    return $;
  }
  static Expression notOfLiteral(final BooleanLiteral l) {
    final BooleanLiteral $ = duplicate(l);
    $.setBooleanValue(!l.booleanValue());
    return $;
  }
  static Expression parenthesize(final Expression e) {
    if (Is.simple(e))
      return duplicate(e);
    final ParenthesizedExpression $ = e.getAST().newParenthesizedExpression();
    $.setExpression(duplicate(getCore(e)));
    return $;
  }
  static Expression perhapsComparison(final Expression inner) {
    return perhapsComparison(asComparison(inner));
  }
  static Expression perhapsComparison(final InfixExpression inner) {
    return inner == null ? null : comparison(inner);
  }
  static Expression perhapsDeMorgan(final Expression e) {
    return perhapsDeMorgan(asAndOrOr(e));
  }
  static Expression perhapsDeMorgan(final InfixExpression e) {
    return e == null ? null : deMorgan(e, getCoreLeft(e), getCoreRight(e));
  }
  static Expression perhapsDoubleNegation(final Expression inner) {
    return perhapsDoubleNegation(asNot(inner));
  }
  static Expression perhapsDoubleNegation(final PrefixExpression inner) {
    return inner == null ? null : inner.getOperand();
  }
  static Expression perhapsNotOfLiteral(final Expression inner) {
    return !Is.booleanLiteral(inner) ? null : notOfLiteral(asBooleanLiteral(inner));
  }
  static Expression pushdownNot(final Expression inner) {
    Expression $;
    return ($ = perhapsNotOfLiteral(inner)) != null//
        || ($ = perhapsDoubleNegation(inner)) != null//
        || ($ = perhapsDeMorgan(inner)) != null//
        || ($ = perhapsComparison(inner)) != null //
            ? $ : null;
  }
  static Expression pushdownNot(final PrefixExpression e) {
    return e == null ? null : pushdownNot(getCore(e.getOperand()));
  }
  /**
   * Consider an expression <code> a ? b : c </code>; in a sense it is the same
   * as <code> (a && b) || (!a && c) </code>
   * <ol>
   * <li>if b is false then: <code>
  * (a && false) || (!a && c) == !a && c </code>
   * <li>if b is true then:
   * <code>(a && true) || (!a && c) == a || (!a && c) == a || c </code>
   * <li>if c is false then: <code>(a && b) || (!a && false) == a && b </code>
   * <li>if c is true then <code>(a && b) || (!a && true) == !a || b</code>
   * </ol>
   */
  static Expression simplifyTernary(final ConditionalExpression e) {
    return simplifyTernary(getCore(e.getThenExpression()), getCore(e.getElseExpression()), duplicate(e.getExpression()));
  }
  /* <code> a ? b : c </code>
   *
   * is the same as
   *
   * <code> (a && b) || (!a && c) </code>
   *
   * if b is false than:
   *
   * <code> (a && false) || (!a && c) == (!a && c) </code>
   *
   * if b is true than:
   *
   * <code> (a && true) || (!a && c) == a || (!a && c) == a || c </code>
   *
   * if c is false than:
   *
   * <code> (a && b) || (!a && false) == (!a && c) </code>
   *
   * if c is true than
   *
   * <code> (a && b) || (!a && true) == (a && b) || (!a) == !a || b </code> */
  static void simplifyTernary(final List<Expression> es) {
    for (int i = 0; i < es.size(); ++i) {
      final Expression e = es.get(i);
      if (!isTernaryOfBooleanLitreral(e))
        continue;
      es.remove(i);
      es.add(i, simplifyTernary(asConditionalExpression(e)));
    }
  }
  static boolean tryToSort(final List<Expression> es, final java.util.Comparator<Expression> c) {
    boolean $ = false;
    // Bubble sort, duplicating in each case of swap
    for (int i = 0, size = es.size(); i < size; i++)
      for (int j = 0; j < size - 1; j++) {
        final Expression e0 = es.get(j);
        final Expression e1 = es.get(j + 1);
        if (c.compare(e0, e1) <= 0)
          continue;
        // Replace locations i,j with e0 and e1
        es.get(j + 1);
        es.remove(j);
        es.remove(j);
        es.add(j, e0);
        es.add(j, e1);
        $ = true;
      }
    return $;
  }
  final Wring inner;
  Wrings(final Wring inner) {
    this.inner = inner;
  }
}
