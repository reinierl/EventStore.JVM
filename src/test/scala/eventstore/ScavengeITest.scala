package eventstore

/**
 * @author Yaroslav Klymko
 */
class ScavengeITest extends TestConnection {
  "scavenge" should {
    "scavenge database" in new TestConnectionScope {
      actor ! ScavengeDatabase
      // TODO how to verify?
    }
  }
}
