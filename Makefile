# Makefile (Linux/macOS)
# Purpose:
# - Compile application sources from src/main/java into ./out
# - Optionally compile test sources from src/test/java and run them with JUnit 5
# - Provide convenient targets to run the server/client
#
# Notes:
# - This Makefile assumes a JDK is installed and 'javac'/'java' are on PATH.
# - Classpath separator ':' is correct for Linux/macOS. On Windows use ';'.
# - JUnit Console Standalone JAR is expected at lib/junit-platform-console-standalone-*.jar.

JAVAC := javac          # Java compiler
JAVA  := java           # Java launcher

OUT := out
SRC_MAIN := src/main/java
SRC_TEST := src/test/java

# Auto-detect the JUnit Console jar and strip any stray spaces
JUNIT_JAR := $(strip $(firstword $(wildcard lib/junit-platform-console-standalone*.jar)))
# Fail early with a clear error if not found
ifeq ($(JUNIT_JAR),)
  $(error JUnit Console jar not found in ./lib. Place junit-platform-console-standalone-<version>.jar under lib/)
endif

# Build clean classpaths (no padding)
CP_APP := $(strip $(OUT))
CP_TEST := $(strip $(OUT):$(JUNIT_JAR))

.PHONY: all app tests run-server run-client test clean

# Default target: build and run tests
all: tests

# Compile application sources only
app: clean
	@echo "Compiling application sources from $(SRC_MAIN) -> $(OUT)"
	mkdir -p $(OUT)
	find $(SRC_MAIN) -name '*.java' -print0 | xargs -0 $(JAVAC) -d $(OUT)

# Compile app + tests, then run tests
tests: clean
	@echo "Compiling application sources..."
	mkdir -p $(OUT)
	find $(SRC_MAIN) -name '*.java' -print0 | xargs -0 $(JAVAC) -d $(OUT)
	@echo "Using JUnit jar: $(JUNIT_JAR)"
	@echo "Compiling test sources with classpath: $(CP_TEST)"
	# Collect test sources into an argfile to avoid xargs/quoting issues
	find $(SRC_TEST) -name '*.java' > .test-sources
	$(JAVAC) -cp "$(CP_TEST)" -d $(OUT) @.test-sources
	rm -f .test-sources
	@echo "Running tests..."
	$(JAVA) -jar "$(JUNIT_JAR)" -cp "$(OUT)" --scan-classpath

# Run the server (builds app first if needed)
run-server: app
	@echo "Starting server..."
	$(JAVA) -cp "$(CP_APP)" org.example.CalculatorServer

# Run the client (builds app first if needed)
run-client: app
	@echo "Starting client..."
	$(JAVA) -cp "$(CP_APP)" org.example.CalculatorClient

# Alias
test: tests

# Clean compiled output
clean:
	@echo "Cleaning output directory..."
	rm -rf "$(OUT)"