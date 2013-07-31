/*
 * This file is part of the sbt-rats plugin.
 * Copyright (c) 2012-2013 Anthony M Sloane, Macquarie University.
 * All rights reserved.
 * Distributed under the New BSD license.
 * See file LICENSE at top of distribution.
 */

import org.kiama.output.PrettyPrinter

/**
 * Generator of auxiliary files from a syntax specification, parameterised
 * by the analyser to use.
 */
class Generator (analyser : Analyser) extends PrettyPrinter {

    import analyser.{constr, elemtype, fieldName, fieldTypes, isLinePP,
        isParenPP, isTransferAlt, lhs, orderOpPrecFixityNonterm,
        requiresNoPPCase, treeAlternatives, typeName}
    import ast._
    import org.kiama.attribution.Attribution.{initTree, resetMemo}
    import org.kiama.rewriting.Rewriter.{alltd, rewrite, query}
    import sbt.File
    import sbt.IO.write
    import scala.collection.mutable.{HashMap, ListBuffer}

    /**
     * Generate a Scala case class implmentation of the abstract syntax
     * used by the grammar.
     */
    def generateASTClasses (flags : Flags, astFile : File, grammar : Grammar) {

        // The module comoponent of the grammar name
        val module = grammar.module.last

        // The package component of the grammar name
        val pkg = grammar.module.init.mkString (".")

        // The name of the AST module
        val name = module + "Syntax"

        def toAST : Doc =
            "// AUTOMATICALLY GENERATED by sbt-rats - EDIT AT YOUR OWN RISK" <@>
            line <>
            "package" <+> pkg <@>
            toImports <>
            toSyntax <>
            line

        def includeImportWhen (importEntity : String, cond : Boolean) : Doc =
            if (cond)
                "import" <+> importEntity <> line
            else
                empty

        def toImports : Doc =
            line <>
            includeImportWhen ("org.kiama.attribution.Attributable",
                               flags.useKiama) <>
            includeImportWhen ("org.kiama.output.{Infix, LeftAssoc, NonAssoc, Prefix, RightAssoc}",
                               flags.definePrettyPrinter) <>
            includeImportWhen ("org.kiama.output.{PrettyBinaryExpression, PrettyExpression, PrettyUnaryExpression}",
                               flags.definePrettyPrinter) <>
            includeImportWhen ("scala.util.parsing.input.Positional",
                               flags.useScalaPositions && !flags.useKiama) <>
            includeImportWhen ("org.kiama.util.Positioned",
                               flags.useScalaPositions && flags.useKiama)

        def toSyntax : Doc =
            line <>
            "object" <+> name <+> "{" <@>
            nest (
                toSuperClass <@>
                hsep (grammar.rules map toRuleClasses)
            ) <@>
            "}"

        def toSuperClass : Doc = {
            val superTraits = List (
                if (flags.useScalaPositions)
                    List (if (flags.useKiama) "Positioned" else "Positional")
                else
                    Nil,
                if (flags.useKiama)
                    List ("Attributable")
                else
                    Nil
            ).flatten

            line <>
            "abstract class ASTNode extends" <+> hsep (superTraits map text, " with")
        }

        def toRuleClasses (rule : Rule) : Doc =
            rule match {
                case r : ASTRule => toASTRuleClasses (r)
                case _           => empty
            }

        def toASTRuleClasses (astRule : ASTRule) : Doc = {

            def toFields (alt : Alternative) : Doc = {

                /**
                 * Representation of a field by its name and its type.
                 */
                case class Field (name : String, tipe : String)

                /**
                 * List of fields in the process of being built.
                 */
                val fields = ListBuffer[Field] ()

                /**
                 * Add a field for the given element if it's not Void.
                 */
                def addField (elem : Element) {
                    if (elem->elemtype != "Void") {
                        val fieldNum = fields.length + 1
                        val fieldType = (alt->fieldTypes).getOrElse (fieldNum, elem->elemtype)
                        fields.append (Field (elem->fieldName, fieldType))
                    }
                }

                /**
                 * Traverse the elements on the RHS of the rule to collect fields.
                 */
                def traverseRHS (elems : List[Element]) {

                    def traverseElem (elem : Element) {
                        elem match {
                            case Block (name, _) =>
                                fields.append (Field (elem->fieldName, "String"))
                            case _ : NonTerminal =>
                                addField (elem)
                            case Opt (innerElem) =>
                                val optElem =
                                    if (flags.useScalaOptions)
                                        elem
                                    else
                                        innerElem
                                addField (optElem)
                            case Nest (nestedElem) =>
                                addField (nestedElem)
                            case _ : Rep =>
                                addField (elem)
                            case Seqn (l, r) =>
                                traverseElem (l)
                                traverseElem (r)
                            case _ =>
                                // No argument for the rest of the element kinds
                        }
                    }

                    elems.map (traverseElem)

                }

                // Traverse the RHS elememts to collect field information
                traverseRHS (alt.rhs)

                // Set of non-unique field names
                val nonUniqueFieldNames =
                    fields.map (_.name).groupBy (s => s).collect {
                        case (n, l) if l.length > 1 =>
                            n
                    }.toSet

                /**
                 * Is the field name not unique in this field list?
                 */
                def isNotUnique (fieldName : String) : Boolean =
                    nonUniqueFieldNames contains fieldName

                /**
                 * Make the field names unique by numbering fields whose names are
                 * not unique.
                 */
                val uniqueFields =
                    fields.result.foldLeft (Map[String,Int] (), List[Field] ()) {
                        case ((m, l), f @ Field (n, t)) =>
                            if (isNotUnique (n)) {
                                val i = m.getOrElse (n, 0) + 1
                                (m.updated (n, i), Field (n + i.toString, t) :: l)
                            } else
                                (m, f :: l)
                    }

                /**
                 * Convert the final field list to a list of documents.
                 */
                val fieldDocs =
                    uniqueFields._2.reverse map {
                        case Field (n, t) => n <+> colon <+> t
                    }

                // Assemble final document for argument list
                parens (hsep (fieldDocs, comma))

            }

            def toParenPPInfo (alt : Alternative) : Doc =
                if (flags.definePrettyPrinter && (astRule->isParenPP))
                    (alt->orderOpPrecFixityNonterm) match {
                        case Some ((order, op, prec, fixity, nt1, nt2)) =>
                            (order match {
                                 case 1 =>
                                    text ("with PrettyUnaryExpression ")
                                 case 2 =>
                                    text ("with PrettyBinaryExpression ")
                                 case _ =>
                                    sys.error ("toParenPPInfo: unexpected order " + order)
                             }) <>
                            braces (
                                nest (
                                    line <>
                                    "val priority =" <+> value (prec) <@>
                                    "val op =" <+> dquotes (op) <@>
                                    "val fixity =" <+> value (fixity) <>
                                    (order match {
                                        case 1 =>
                                            if (nt1 == "exp")
                                                empty
                                            else
                                                line <>
                                                "val exp =" <+> nt1
                                        case 2 =>
                                            line <>
                                            "val left =" <+> nt2 <> "1" <@>
                                            "val right =" <+> nt2 <> "2"
                                     })
                                ) <>
                                line
                            )
                        case _ =>
                            empty
                    }
                else
                    empty

            def toConcreteClass (parent : String) (alt : Alternative) : Doc =
                "case class" <+> text (alt->constr) <+> toFields (alt) <+> "extends" <+>
                    parent <+> toParenPPInfo (alt)

            // Common super class clause
            val superClass =
                "extends" <+> "ASTNode" <>
                (if (flags.definePrettyPrinter && (astRule->isParenPP))
                     " with PrettyExpression"
                 else
                     empty)

            // All alternatives that involve tree construction
            val treeAlts = astRule->treeAlternatives

            val ASTRule (lhs, tipe, alts, _, _) = astRule

            line <>
            (if (alts.length == 1)
                if (alts.head->isTransferAlt)
                    "sealed abstract class" <+> lhs.name <+> superClass
                else
                    if (astRule.tipe == null)
                        "case class" <+> lhs.name <+> toFields (alts.head) <+> superClass
                    else
                        toConcreteClass (tipe.name) (alts.head)
             else
                if (astRule.tipe == null)
                    "sealed abstract class" <+> lhs.name <+> superClass <@>
                    vsep (treeAlts map (toConcreteClass (lhs.name)))
                else
                    vsep (treeAlts map (toConcreteClass (tipe.name)))) <>
            line

        }

        // Initialise the tree so we can perform attribution on it
        resetMemo ()
        initTree (grammar)

        // Put together top level code
        val code = pretty (toAST)

        // Put the code in the specified file
        write (astFile, code)

    }

    /**
     * Generate a Scala implmentation of a pretty printer for the AST classes
     * using the Kiama library. Assumes that AST classes have been generated.
     */
    def generatePrettyPrinter (flags : Flags, ppFile : File, grammar : Grammar) {

        // The module component of the grammar name
        val module = grammar.module.last

        // The package component of the grammar name
        val pkg = grammar.module.init.mkString (".")

        // The name of the pretty printer
        val name = module + "PrettyPrinter"

        // Buffer of cases that need to appear in a toParenDoc function since
        // they handle automatically parenthesized nodes.
        val toParenDocCases = ListBuffer[Doc] ()

        def toPP : Doc =
            "// AUTOMATICALLY GENERATED by sbt-rats - EDIT AT YOUR OWN RISK" <@>
            line <>
            "package" <+> pkg <@>
            line <>
            "import" <+> pkg <> "." <> module <> "Syntax._" <@>
            "import org.kiama.output.{PrettyExpression, PrettyPrinter => PP, ParenPrettyPrinter => PPP}" <@>
            toPrettyPrinter <>
            line

        def toPrettyPrinter : Doc = {

            // Force the toDoc function to be built first since as it is built we
            // need to collect cases for toToParenDoc. If we do it inline we have
            // to rely the order of evaluation of the args of <@>.
            val toDoc = toToDoc

            line <>
            "trait" <+> name <+> "extends PP with PPP {" <@>
            nest (
                toPretty <@>
                toDoc <@>
                toToParenDoc (toParenDocCases.result)
            ) <@>
            line <>
            "}" <>
            line <@>
            "object" <+> name <+> "extends" <+> name <>
            line

        }

        def toPretty : Doc =
            line <>
            "def pretty (astNode : ASTNode) : String =" <>
            nest (
                line <>
                "super.pretty (group (toDoc (astNode)))"
            )

        def toToDoc : Doc =
            line <>
            "def toDoc (astNode : ASTNode) : Doc =" <>
            nest (
                line <>
                "astNode match {" <>
                nest (
                    hsep (grammar.rules map toToDocCase) <@>
                    "case _ => empty"
                ) <@>
                "}"
            )

        def toToParenDoc (cases : List[Doc]) : Doc =
            if (cases == Nil)
                empty
            else
                line <>
                "override def toParenDoc (v : PrettyExpression) : Doc =" <>
                nest (
                    line <>
                    "v match {" <>
                    nest (
                        hsep (cases) <@>
                        "case _ =>" <>
                        nest (
                            line <>
                            "super.toParenDoc (v)"
                        )
                    ) <@>
                    "}"
                )

        def toToDocCase (rule : Rule) : Doc =
            rule match {
                case r : ASTRule => toASTRuleToDocCase (r)
                case _           => empty
            }

        def toASTRuleToDocCase (astRule : ASTRule) : Doc = {

            val tipe = astRule->typeName
            val tipeDoc = text (tipe)

            def toAlternativeToDocCase (alt : Alternative) : Doc = {

                // FIXME: duplicates some logic from elsewhere
                // Count of variables in the pattern
                var varcount = 0

                /**
                 * Make a variable name from a variable count.
                 */
                def varName (count : Int) =
                    text ("v%d".format (count))


                /**
                 * Traverse the elements on the RHS of the rule to collect pattern
                 * variables and the Doc expression.
                 */
                def traverseRHS (elems : List[Element]) : List[Doc] = {

                    /**
                     * Create pretty-printing code for an element that needs to
                     * be mapped.
                     */
                    def traverseMap (innerElem : Element, optSep : Option[Element] = None) : Doc = {
                        val doOne = traverseElem (innerElem)
                        val varr = varName (varcount)
                        val mapper =
                            innerElem match {
                                case _ : Seqn =>
                                    varr <> ".map" <+> parens (varr <+> "=>" <+> doOne)
                                case _ =>
                                    val func =
                                        if (innerElem->elemtype == "String")
                                            "text"
                                        else
                                            "toDoc"
                                    varr <> ".map" <+> parens (func)
                            }
                        optSep match {
                            case Some (sep) =>
                                "ssep" <+> parens (mapper <> comma <+> traverseElem (sep))
                            case None =>
                                mapper <> ".getOrElse (empty)"
                        }
                    }

                    def traverseElem (elem : Element, wrap : Boolean = true) : Doc =
                        elem match {

                            case CharLit (s) =>
                                if (s.length == 1)
                                    "char" <+> parens (squotes (s))
                                else
                                    "text" <+> parens (dquotes (s))

                            case Epsilon () =>
                                text ("empty")

                            case _ : NonTerminal =>
                                if (elem->elemtype == "Void")
                                    text ("empty")
                                else {
                                    varcount = varcount + 1
                                    var varr = varName (varcount)
                                    if (wrap) {
                                        val args = parens (varr)
                                        if (elem->elemtype == "String")
                                            "value" <+> args
                                        else
                                            "toDoc" <+> args
                                    } else
                                        varr
                                }

                            case Opt (innerElem) =>
                                if (elem->elemtype == "Void")
                                    text ("empty")
                                else
                                    traverseMap (innerElem)

                            case Rep (_, innerElem, sep) =>
                                if (elem->elemtype == "Void")
                                    text ("empty")
                                else
                                    traverseMap (innerElem, Some (sep))

                            case Seqn (l, r) =>
                                traverseElem (l) <+> "<>" <+> traverseElem (r)

                            case StringLit (s) =>
                                "text" <+> parens (dquotes (s)) <+> "<> space"

                            // Formatting elements
                            case Nest (e) =>
                                "nest" <+> parens (traverseElem (e))
                            case Newline () =>
                                text ("line")
                            case Space () =>
                                text ("space")

                            case _ =>
                                sys.error ("traverseElem: saw unexpected elem " + elem)
                        }

                    elems match {
                        case Nil => List (text ("empty"))
                        case _   => elems.map (e => traverseElem (e))
                    }

                }

                if (alt->requiresNoPPCase)
                    empty
                else {
                    val exps = traverseRHS (alt.rhs)

                    val pattern = {
                        val vars =
                            (1 to varcount).map {
                                case n => varName (n)
                            }
                        (alt->constr) <+> parens (hsep (vars, comma))
                    }

                    val body =
                        line <>
                        (if (astRule->isLinePP) "line <> " else empty) <>
                            ssep (exps, " <> ")

                    val newCase =
                        line <>
                        "case" <+> pattern <+> "=>" <>
                        nest (
                            body
                        )

                    if (astRule->isParenPP) {
                        toParenDocCases.append (newCase)
                        empty
                    } else
                        newCase
                }

            }

            def toParenPP : Doc =
                line <>
                "case v :" <+> tipeDoc <+> "=>" <>
                nest (
                    line <>
                    "toParenDoc (v)"
                )

            // Process each case. Also, if the rule is an explicitly parenthesized one,
            // add a case to send execution to the toParenDoc func for this type.
            hsep (astRule.alts map (toAlternativeToDocCase)) <>
            (if ((tipe == astRule->lhs) && astRule->isParenPP)
                 toParenPP
             else
                 empty)

        }

        // Put together top level code.
        val code = pretty (toPP)

        // Put the code in the specified file
        write (ppFile, code)

    }

    /**
     * Generate module of supporting code for Scala-based parsers.
     */
    def generateSupportFile (flags : Flags, supportFile : File) {

        val contents = """
            |// AUTOMATICALLY GENERATED by sbt-rats - EDIT AT YOUR OWN RISK
            |
            |package sbtrats
            |
            |trait Action[T] {
            |    def run (arg : T) : T
            |}
            |
            |object ParserSupport {
            |
            |    def apply[T] (actions : List[Action[T]], seed : T) : T = {
            |        var result = seed
            |        for (action <- actions) {
            |            result = action.run (result)
            |        }
            |        result
            |    }
            |
            |}
            |""".stripMargin

        write (supportFile, contents)

        if (flags.useScalaPositions) {

            val lineColContents =
                """
                |import scala.util.parsing.input.{Position, Positional}
                |
                |class LineColPosition (val line : Int, val column : Int) extends Position {
                |
                |    override def < (that : Position) : Boolean =
                |        line < that.line ||
                |            line == that.line && column < that.column
                |
                |    override def lineContents : String =
                |        throw new RuntimeException ("LineColPosition.lineContents not implemented")
                |
                |    override def longString : String =
                |        throw new RuntimeException ("LineColPosition.longString not implemented")
                |
                |    override def toString () : String =
                |        "" + line + "." + column
                |
                |}
                |""".stripMargin

            write (supportFile, lineColContents, append = true)

        }

    }

}
