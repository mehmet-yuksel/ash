import mill._, scalalib._

object linear extends ScalaModule {
  def scalaVersion = "3.3.1"

  object test extends ScalaTests {
    def ivyDeps = Agg(ivy"com.lihaoyi::utest:0.8.5")
    def testFramework = "utest.runner.Framework"
  }
}
