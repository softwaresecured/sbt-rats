/*
 * This file is part of the sbt-rats plugin.
 * Copyright (c) 2012-2015 Anthony M Sloane, Macquarie University.
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

    import analyser.{associativity, constr, elemtype, Field, fieldName,
        fieldTypes, fields, isLeftRecursive, isLinePP, isNestedPP, isParenPP,
        isRightRecursive, isTransferAlt, lhs, precFixity, orderOpPrecFixityNonterm,
        requiresNoPPCase, treeAlternatives, typeName}
    import ast._
    import org.kiama.attribution.Attribution.{initTree, resetMemo}
    import org.kiama.output.{LeftAssoc, NonAssoc, RightAssoc, Side}
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
        val name = s"${module}Syntax"

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
                               flags.useKiama == 1) <>
            includeImportWhen ("scala.util.parsing.input.Positional",
                               flags.useScalaPositions && (flags.useKiama == 0))

        def toSyntax : Doc =
            line <>
            "object" <+> name <+> "{" <@>
            nest (
                toSuperClass <@>
                hsep (grammar.rules map toRuleClasses)
            ) <@>
            "}"

        def toSuperClass : Doc = {
            val superTraits =
                List ("Product") ++
                (if (flags.useScalaPositions && (flags.useKiama == 0))
                    List ("Positional")
                else
                    Nil) ++
                (if (flags.useKiama == 1)
                    List ("Attributable")
                else
                    Nil)

            line <>
            "sealed abstract class ASTNode extends" <+> hsep (superTraits map text, " with")
        }

        def toRuleClasses (rule : Rule) : Doc =
            rule match {
                case r : ASTRule => toASTRuleClasses (r)
                case _           => empty
            }

        def toASTRuleClasses (astRule : ASTRule) : Doc = {

            def toFields (alt : Alternative) : Doc = {
                val fieldDocs =
                    (alt->fields).map {
                        case Field (n, t, _) => n <+> colon <+> t
                    }
                parens (hsep (fieldDocs, comma))
            }

            /*
             * Returns two components: one list of extensions that the class for
             * this alternative needs, and one list of definitions that go in the
             * class.
             */
            def toParenPPInfo (alt : Alternative) : (List[Doc], List[Doc]) =
                if (flags.definePrettyPrinter && (astRule->isParenPP))
                    if (flags.useKiama == 1)
                        (alt->orderOpPrecFixityNonterm) match {
                            case Some ((order, op, prec, fixity, nt1, nt2)) =>
                                (
                                 List (
                                     order match {
                                         case 1 =>
                                             text (s"with ${flags.kiamaPkg}.output.PrettyUnaryExpression ")
                                         case 2 =>
                                             text (s"with ${flags.kiamaPkg}.output.PrettyBinaryExpression ")
                                         case _ =>
                                             sys.error (s"toParenPPInfo: unexpected order $order")
                                     }
                                 ),
                                 List (
                                     "val priority =" <+> value (prec),
                                     "val op =" <+> dquotes (op),
                                     "val fixity =" <+> value (fixity)
                                 ) ++
                                     (order match {
                                         case 1 =>
                                             if (nt1 == "exp")
                                                 Nil
                                             else
                                                 List ("val exp =" <+> nt1)
                                         case 2 =>
                                             List (
                                                 "val left =" <+> nt2 <> "1",
                                                 "val right =" <+> nt2 <> "2"
                                             )
                                      })
                                )
                            case None =>
                                (Nil, Nil)
                        }
                    else if (flags.useKiama == 2)
                        (alt->precFixity) match {
                            case (prec, fixity) =>
                                (
                                 List (text (s"with ${flags.kiamaPkg}.output.PrettyNaryExpression")),
                                 List (
                                     "val priority =" <+> value (prec),
                                     "val fixity =" <+> value (fixity)
                                 )
                                )
                        }
                    else
                        (Nil, Nil)
                else
                    (Nil, Nil)

            def toRequires (alt : Alternative) : List[Doc] =
                (alt->fields).collect {
                    case Field (n, t, false) =>
                        "require" <+> parens (
                            n <> ".length > 0" <> comma <+>
                            s""""$n field can't be empty""""
                        )
                }

            def toConcreteClass (parent : String) (alt : Alternative) : Doc = {
                val (ppext, pp) = toParenPPInfo (alt)
                val req = toRequires (alt)
                val bodylines = if (pp.isEmpty) vsep (req)
                                else if (req.isEmpty) vsep (pp)
                                else vsep (pp) <@> vsep (req)
                val body = if (pp.isEmpty && req.isEmpty)
                               empty
                           else
                               braces (
                                   nest (
                                       line <>
                                       bodylines
                                   ) <>
                                   line
                               )
                "case class" <+> text (alt->constr) <+> toFields (alt) <+> "extends" <+>
                    parent <+> hsep (ppext) <+> body
            }

            // Common super class clause
            val superClass =
                "extends" <+> "ASTNode" <>
                (if (flags.definePrettyPrinter && (astRule->isParenPP))
                     s" with ${flags.kiamaPkg}.output.PrettyExpression"
                 else
                     empty)

            // All alternatives that involve tree construction
            val treeAlts = astRule->treeAlternatives

            val ASTRule (lhs, tipe, alts, _, _) = astRule

            val baseClass = "sealed abstract class" <+> lhs.name <+> superClass

            line <>
            (if (alts.length == 1) {
                val alt = alts.head
                if (alt->isTransferAlt)
                    baseClass
                else
                    if (astRule.tipe == null)
                        if (lhs.name == alt->constr)
                            "case class" <+> text (alt->constr) <+> toFields (alt) <+> superClass
                        else
                            baseClass <@>
                            toConcreteClass (lhs.name) (alt)
                    else
                        toConcreteClass (tipe.name) (alt)
             } else
                if (astRule.tipe == null)
                    baseClass <@>
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
        val name = s"${module}PrettyPrinter"

        // The empty document to use
        val emptyDocText = text (if (flags.useKiama >= 2) "emptyDoc" else "empty")

        // Buffer of cases that need to appear in a toParenDoc function since
        // they handle automatically parenthesized nodes.
        val toParenDocCases = ListBuffer[Doc] ()

        def toPP : Doc =
            "// AUTOMATICALLY GENERATED by sbt-rats - EDIT AT YOUR OWN RISK" <@>
            line <>
            "package" <+> pkg <@>
            line <>
            "import" <+> pkg <> "." <> module <> "Syntax._" <@>
            s"import ${flags.kiamaPkg}.output.{PrettyPrinter => PP, ParenPrettyPrinter => PPP}" <@>
            (if (flags.useKiama == 2)
                "import org.bitbucket.inkytonik.kiama.output.PrettyPrinterTypes.{Document, Width}"
             else
                 empty) <@>
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
                toOptions <@>
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

        def toOptions : Doc = {

            def getOption (func : PartialFunction[SyntaxOption,Int]) =
                grammar.options.foldLeft[Option[Int]] (None) {
                    case (previous, option) =>
                        if (func.isDefinedAt (option))
                            Some (func (option))
                        else
                            previous
                }

            def makeOption (optn : Option[Int], name : String) : Doc =
                optn.map {
                    case n =>
                        line <> "override" <+> "val" <+> name <+> "=" <+> value (n)
                }.getOrElse (
                    empty
                )

            lazy val indentationOptionValue = getOption {case Indentation (n) => n}
            lazy val widthOptionValue = getOption {case Width (n) => n}

            if (grammar.options == null)
                empty
            else
                makeOption (indentationOptionValue, "defaultIndent") <>
                makeOption (widthOptionValue, "defaultWidth")

        }

        def toPretty : Doc =
            (if (flags.useKiama == 1)
                    empty
                else
                    line <>
                    "def show (astNode : ASTNode, w : Width = defaultWidth) : String =" <>
                         nest (line <> "format (astNode, w).layout") <> line) <>
            line <>
            "def format (astNode : ASTNode, w : Width = defaultWidth) : " <>
                (if (flags.useKiama == 1) "String" else "Document") <+> "=" <>
            nest (
                line <>
                (if (flags.useKiama == 1)
                    "super.pretty"
                 else
                    "pretty") <+> "(group (toDoc (astNode)), w)"
            )

        def toToDoc : Doc =
            line <>
            "def toDoc (astNode : ASTNode) : Doc =" <>
            nest (
                line <>
                "astNode match {" <>
                    nest (
                        if (grammar.rules.isEmpty)
                            "case _ => " <> emptyDocText
                        else
                            hsep (grammar.rules map toToDocCase)
                    ) <@>
                "}"
            )

        def toToParenDoc (cases : List[Doc]) : Doc =
            if (cases == Nil)
                empty
            else
                line <>
                s"override def toParenDoc (astNode : ${flags.kiamaPkg}.output.PrettyExpression) : Doc =" <>
                nest (
                    line <>
                    "astNode match {" <>
                    nest (
                        hsep (cases) <@>
                        "case _ =>" <>
                        nest (
                            line <>
                            "super.toParenDoc (astNode)"
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
                    text (s"v$count")

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
                                mapper <> ".getOrElse" <+> parens(emptyDocText)
                        }
                    }

                    def traverseChild (elem : Element, varr : Doc) : Doc = {

                        def recursiveCall (side : Side) : Doc = {
                            val qualSide = s"${flags.kiamaPkg}.output.$side"
                            "recursiveToDoc" <+> parens ("v," <+> varr <> "," <+> value (qualSide))
                        }

                        if (elem.index == 0)
                            recursiveCall (LeftAssoc)
                        else if (elem.index == elems.length - 1)
                            recursiveCall (RightAssoc)
                        else
                            recursiveCall (NonAssoc)

                    }

                    def traverseElem (elem : Element, wrap : Boolean = true) : Doc =
                        elem match {

                            case CharLit (lit) =>
                                "text" <+> parens (dquotes (lit.escaped))

                            case Epsilon () =>
                                emptyDocText

                            case NonTerminal (NTName (IdnUse (nt))) =>
                                if (elem->elemtype == "Void")
                                    emptyDocText
                                else {
                                    varcount = varcount + 1
                                    var varr = varName (varcount)
                                    if (wrap) {
                                        val args = parens (varr)
                                        if ((elem->elemtype == "String") ||
                                            (elem->elemtype == "Token"))
                                            "value" <+> args
                                        else if (astRule->isParenPP &&
                                                 (flags.useKiama == 2) &&
                                                 (nt == astRule->lhs))
                                            traverseChild (elem, varr)
                                        else
                                            "toDoc" <+> args
                                    } else
                                        varr
                                }

                            case Opt (innerElem) =>
                                if (elem->elemtype == "Void")
                                    emptyDocText
                                else
                                    traverseMap (innerElem)

                            case Rep (_, innerElem, sep) =>
                                if (elem->elemtype == "Void")
                                    emptyDocText
                                else
                                    traverseMap (innerElem, Some (sep))

                            case Seqn (l, r) =>
                                traverseElem (l) <+> "<>" <+> traverseElem (r)

                            case StringLit (lit) =>
                                "text" <+> parens (dquotes (lit.escaped)) <+> "<> space"

                            // Formatting elements
                            case Nest (e, newline) =>
                                val body =
                                    (if (newline)
                                        text ("line <> ")
                                     else
                                        empty) <>
                                    traverseElem (e)
                                "nest" <+> parens (body)
                            case Newline () =>
                                text ("line")
                            case Space () =>
                                text ("space")

                            case _ =>
                                sys.error (s"traverseElem: saw unexpected elem $elem")
                        }

                    elems match {
                        case Nil => List (emptyDocText)
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

                    val rhs =
                        (if (astRule->isLinePP) text ("line <> ") else empty) <>
                        ssep (exps, " <> ")

                    val body =
                        line <>
                        (if (astRule->isNestedPP)
                            "nest" <+> parens (rhs)
                         else
                             rhs)

                    val newCase =
                        line <>
                        "case" <+> "v" <+> "@" <+> pattern <+> "=>" <>
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

        val lineColPositionContents =
            """
            |import scala.util.parsing.input.Position
            |import xtc.parser.ParserBase
            |
            |class LineColPosition (val p : ParserBase, val index : Int, val line : Int, val column : Int) extends Position {
            |
            |    override def < (that : Position) : Boolean =
            |        line < that.line ||
            |            line == that.line && column < that.column
            |
            |    override def lineContents : String =
            |        p.lineAt (index)
            |
            |    override def toString () : String =
            |        s"${line}.${column}"
            |
            |}
            |""".stripMargin

        val contents = s"""
            |// AUTOMATICALLY GENERATED by sbt-rats - EDIT AT YOUR OWN RISK
            |
            |package sbtrats
            |${if (flags.useScalaPositions && (flags.useKiama != 2)) lineColPositionContents else ""}
            |trait Action[T] {
            |    def run (arg : T) : T
            |}
            |
            |object SList {
            |    type L[T] = scala.collection.immutable.List[T]
            |    def empty[T] : L[T] = Nil
            |    def create[T] (hd : T) : L[T] = hd :: Nil
            |    def create[T] (hd : T, nxt : T) : L[T] = hd :: nxt :: Nil
            |    def create[T] (hd : T, tl : L[T]) : L[T] = hd :: tl
            |    def reverse[T] (l : L[T]) : L[T] = l.reverse
            |}
            |
            |object SVector {
            |    type L[T] = scala.collection.immutable.Vector[T]
            |    def empty[T] : L[T] = Vector ()
            |    def create[T] (hd : T) : L[T] = hd +: Vector ()
            |    def create[T] (hd : T, nxt : T) : L[T] = hd +: nxt +: Vector ()
            |    def create[T] (hd : T, tl : L[T]) : L[T] = hd +: tl
            |    def reverse[T] (l : L[T]) : L[T] = l.reverse
            |}
            |
            |object ParserSupport {
            |
            |    def apply[T] (actions : Seq[Action[T]], seed : T) : T =
            |        actions.foldLeft (seed) {
            |            case (result, action) =>
            |                action.run (result)
            |        }
            |
            |}
            |
            |""".stripMargin

        write (supportFile, contents)

    }

}
