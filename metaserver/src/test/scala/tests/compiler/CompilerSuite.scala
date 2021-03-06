package tests.compiler

import scala.meta.languageserver.Uri
import scala.meta.languageserver.compiler.Cursor
import scala.meta.languageserver.compiler.ScalacProvider
import scala.tools.nsc.interactive.Global
import tests.MegaSuite

class CompilerSuite extends MegaSuite {
  val compiler: Global = ScalacProvider.newCompiler(
    "",
    "-deprecation" ::
      "-Ywarn-unused-import" ::
      Nil
  )

  private def computeChevronPositionFromMarkup(
      filename: String,
      markup: String
  ): Cursor = {
    val chevrons = "<<(.*?)>>".r
    val carets0 =
      chevrons.findAllIn(markup).matchData.map(m => (m.start, m.end)).toList
    val carets = carets0.zipWithIndex.map {
      case ((s, e), i) => (s - 4 * i, e - 4 * i - 4)
    }
    carets match {
      case (start, end) :: Nil =>
        val code = chevrons.replaceAllIn(markup, "$1")
        Cursor(Uri.file(filename), code, start)
      case els =>
        throw new IllegalArgumentException(
          s"Expected one chevron, found ${els.length}"
        )
    }
  }

  /**
   * Utility to test the presentation compiler with a position.
   *
   * Use it like like this:
   * {{{
   *   targeted(
   *     "apply",
   *     "object Main { Lis<<t>>", { arg =>
   *       assert(compiler.typeCompletionsAt(arg) == "List" :: Nil)
   *     }
   *   )
   * }}}
   * The `<<t>>` chevron indicates the callback position.
   *
   * See SignatureHelpTest for more inspiration on how to abstract further on
   * top of this method.
   */
  def targeted(
      filename: String,
      markup: String,
      fn: Cursor => Unit
  ): Unit = {
    test(filename.replace(' ', '-')) {
      val point =
        computeChevronPositionFromMarkup(filename + ".scala", markup)
      fn(point)
    }
  }

}
