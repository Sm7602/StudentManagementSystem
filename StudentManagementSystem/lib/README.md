# Libraries

The project uses plain `javac` / `java` (no build tool), so the required JARs live here.
`run.sh` / `run.bat` put `lib/*` on the classpath; the test scripts add `lib/test/*`.

| JAR | Purpose | Maven coordinates (official download) | License |
|---|---|---|---|
| `mysql-connector-j-9.7.0.jar` | JDBC driver for MySQL | `com.mysql:mysql-connector-j:9.7.0` | GPL-2.0 with Universal FOSS Exception |
| `commons-dbcp2-2.10.0.jar` | JDBC connection pool (`BasicDataSource`) | `org.apache.commons:commons-dbcp2:2.10.0` | Apache-2.0 |
| `commons-pool2-2.11.1.jar` | Object pool used by DBCP2 | `org.apache.commons:commons-pool2:2.11.1` | Apache-2.0 |
| `commons-logging-1.3.0.jar` | Logging facade required by DBCP2 | `commons-logging:commons-logging:1.3.0` | Apache-2.0 |
| `test/junit-platform-console-standalone-1.10.1.jar` | JUnit 5 (Jupiter 5.10.1) + console launcher, tests only | `org.junit.platform:junit-platform-console-standalone:1.10.1` | EPL-2.0 |

Nothing else is needed at runtime: no Spring, Hibernate/JPA, Lombok or web framework.

Java versions: the runtime JARs contain Java 8 bytecode, so the application runs on JDK 11+.
The JUnit console JAR (Ubuntu build) contains shaded `opentest4j-reporting` classes compiled for
Java 12, so `run-tests` needs JDK 12+ (tested with JDK 17 and 21). The official JUnit JAR
(see below) also runs on older JDKs.

## Where these files came from

The build environment used to develop and test this project could not reach Maven Central,
so the JARs were obtained as follows. Every automated test and every captured run in
`docs/evidence/` used exactly these files.

* **Apache Commons DBCP2 / Pool2 / Logging and JUnit** - taken from the Ubuntu 24.04 packages
  `libcommons-dbcp2-java`, `libcommons-pool2-java`, `libcommons-logging-java` and `junit5`,
  which Debian/Ubuntu build from the upstream source releases of the versions listed above.
* **MySQL Connector/J 9.7.0** - compiled from the official source tag `9.7.0` of
  <https://github.com/mysql/mysql-connector-j>, including the bytecode instrumentation steps of
  the official build. Only the JDBC API is included: the optional X DevAPI, OpenTelemetry
  tracing and OCI IAM authentication modules were left out because their third-party build
  dependencies were not available. None of them is used by this project.

## Replacing them with the official binaries (optional)

Any of the JARs can be swapped for the official Maven Central artifact of the same version
(or a newer 8.x/9.x Connector/J, 2.x DBCP2), e.g.

```
https://repo1.maven.org/maven2/com/mysql/mysql-connector-j/9.7.0/mysql-connector-j-9.7.0.jar
https://repo1.maven.org/maven2/org/apache/commons/commons-dbcp2/2.10.0/commons-dbcp2-2.10.0.jar
https://repo1.maven.org/maven2/org/apache/commons/commons-pool2/2.11.1/commons-pool2-2.11.1.jar
https://repo1.maven.org/maven2/commons-logging/commons-logging/1.3.0/commons-logging-1.3.0.jar
https://repo1.maven.org/maven2/org/junit/platform/junit-platform-console-standalone/1.10.1/junit-platform-console-standalone-1.10.1.jar
```

Delete the old file, put the downloaded one in the same folder and run `run-tests` again.
No code or configuration change is required.
