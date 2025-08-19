# Makefile for assignment 1

# Purpose:
# Compile application sources from src/main/java into ./out
# Compile test sources from src/test/java and run them with JUnit 5
# Targets to run server and client for manual terminal interface testing

# Notes:
# This Makefile assumes a JDK (21+) is installed and 'javac'/'java' are on PATH.
# Unit Console Standalone JAR (1.10.0) is expected at lib/junit-platform-console-standalone-*.jar.

JAVAC := javac
JAVA  := java

OUT := out
SRC_MAIN := src/main/java
SRC_TEST := src/test/java

# Detect the junit console jar and remove blank space in the path
JUNIT_JAR := $(strip $(firstword $(wildcard lib/junit-platform-console-standalone*.jar)))

# Build classpath to run client - server (main java implementation files)
CP_APP := $(strip $(OUT))
# Build classpath to compile test files using compiled main files and junit jar
CP_TEST := $(strip $(OUT):$(JUNIT_JAR))

.PHONY: all app tests run-server run-client test clean

# Default target: build and run tests
all: tests

# Compile application main files only
app: clean
	@echo "Compiling main java files from $(SRC_MAIN) -> $(OUT)"
	mkdir -p $(OUT)
	# End each input file name with null character to ensure name correctness
	find $(SRC_MAIN) -name '*.java' -print0 | xargs -0 $(JAVAC) -d $(OUT)

# Compile app files + tests, then run all tests
all-tests: clean app
	@echo "Using junit jar: $(JUNIT_JAR)"
	@echo "Compiling test files with $(CP_TEST)"
	find $(SRC_TEST) -name '*.java' -print0 | xargs -0 javac -cp "$(CP_TEST)" -d $(OUT)
	@echo "Running tests..."
	$(JAVA) -jar "$(JUNIT_JAR)" -cp "$(OUT)" --scan-classpath

# compile test by name
compile-test: app
	@echo "Compiling test: $(TEST)"
	$(JAVAC) -cp "$(CP_TEST)" -d "$(OUT)" "$(SRC_TEST)/$(subst .,/,$(TEST)).java"

# Run individual test files
run-test: compile-test
	@echo "Running test class: $(TEST)"
	$(JAVA) -jar "$(JUNIT_JAR)" -cp "$(OUT)" --select-class="$(TEST)"

# List of test files available
list-tests:
	@echo "Displayed test classes under $(SRC_TEST):"
	@find "$(SRC_TEST)" -name '*Test.java' \
	  | sed -e 's#^$(SRC_TEST)/##' -e 's#/#.#g' -e 's#\.java$$##'

# Run the server
server: app
	@echo "Starting server..."
	$(JAVA) -cp "$(CP_APP)" org.example.CalculatorServer

# Run the client
client: app
	@echo "Starting client..."
	$(JAVA) -cp "$(CP_APP)" org.example.CalculatorClient

# Alias
test: run-test
tests: all-tests
run-server: server
run-client: client

# Clean compiled output
clean:
	@echo "Cleaning output directory..."
	rm -rf "$(OUT)"