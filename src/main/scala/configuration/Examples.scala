package deploylib.configuration

import deploylib.configuration.ValueConverstion._

class ScadsEngineExample(port: Int) extends JavaService("../scads/scalaengine/target/scalaengine-1.0-SNAPSHOT-jar-with-dependencies.jar", "edu.berkeley.cs.scads.storage.JavaEngine", "-p " + port)
