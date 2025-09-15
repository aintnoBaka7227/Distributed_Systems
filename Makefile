# Makefile for Distributed Systems Assignment 2: Must run on linux
#
# Purpose:
# - Compile application sources from src/main/java into ./out
# - Compile test sources from src/test/java and run them with JUnit 5
# - Targets to run server, content server, and client for manual terminal testing
#
# Notes:
# - Requires JDK (21+) with 'javac'/'java' on PATH.
# - Gson JAR expected at lib/gson-*.jar
# - JUnit Console Standalone JAR expected at lib/junit-platform-console-standalone*.jar

JAVAC := javac
JAVA  := java

OUT := out
SRC_MAIN := src/main/java
SRC_TEST := src/test/java

# Detect jars
JUNIT_JAR := $(strip $(firstword $(wildcard lib/junit-platform-console-standalone*.jar)))
GSON_JAR  := $(strip $(firstword $(wildcard lib/gson-*.jar)))

# Build classpaths
CP_APP  := $(OUT):$(GSON_JAR)
CP_TEST := $(OUT):$(JUNIT_JAR):$(GSON_JAR)

.PHONY: all app tests run-server run-content run-client run-content1 run-content2 run-client1 run-client2 test clean

# Default target: run all tests
all: tests

# --- Compile application main files ---
app: clean
	@echo "Compiling main java files from $(SRC_MAIN) -> $(OUT)"
	mkdir -p $(OUT)
	find $(SRC_MAIN) -name '*.java' -print0 | xargs -0 $(JAVAC) -cp "$(GSON_JAR)" -d $(OUT)

# --- Compile tests + run them ---
all-tests: clean app
	@echo "Using junit jar: $(JUNIT_JAR)"
	@echo "Compiling test files with $(CP_TEST)"
	find $(SRC_TEST) -name '*.java' -print0 | xargs -0 $(JAVAC) -cp "$(CP_TEST)" -d $(OUT)
	@echo "Running tests..."
	$(JAVA) -jar "$(JUNIT_JAR)" -cp "$(CP_TEST)" --scan-class-path

# compile specific test by name: make run-test TEST=org.UnitTestings.HttpRequestTest
compile-test: app
	@echo "Compiling test: $(TEST)"
	$(JAVAC) -cp "$(CP_TEST)" -d "$(OUT)" "$(SRC_TEST)/$(subst .,/,$(TEST)).java"

run-test: compile-test
	@echo "Running test class: $(TEST)"
	$(JAVA) -jar "$(JUNIT_JAR)" -cp "$(CP_TEST)" --select-class="$(TEST)"

# List all test classes
list-tests:
	@echo "Available test classes:"
	@find "$(SRC_TEST)" -name '*Test.java' \
	  | sed -e 's#^$(SRC_TEST)/##' -e 's#/#.#g' -e 's#\.java$$##'

# --- Run programs (hardcoded convenience) ---
run-server: app
	@echo "Starting Aggregation Server..."
	$(JAVA) -cp "$(CP_APP)" org.AggregationServer -p 4567

run-content1: app
	@echo "Starting ContentServer with station1..."
	$(JAVA) -cp "$(CP_APP)" org.ContentServer -url http://localhost:4567 -f $(SRC_MAIN)/org/test/station1

run-content2: app
	@echo "Starting ContentServer with station2..."
	$(JAVA) -cp "$(CP_APP)" org.ContentServer -url http://localhost:4567 -f $(SRC_MAIN)/org/test/station2

run-client1: app
	@echo "Starting GETClient for IDS60901..."
	$(JAVA) -cp "$(CP_APP)" org.GETClient -url http://localhost:4567 -sid IDS60901

run-client2: app
	@echo "Starting GETClient (all stations)..."
	$(JAVA) -cp "$(CP_APP)" org.GETClient -url http://localhost:4567

# --- Run programs (flexible, allow ARGS=...) ---
run-server-flex: app
	@echo "Running AggregationServer with args: $(ARGS)"
	$(JAVA) -cp "$(CP_APP)" org.AggregationServer $(ARGS)

run-content: app
	@echo "Running ContentServer with args: $(ARGS)"
	$(JAVA) -cp "$(CP_APP)" org.ContentServer $(ARGS)

run-client: app
	@echo "Running GETClient with args: $(ARGS)"
	$(JAVA) -cp "$(CP_APP)" org.GETClient $(ARGS)

# --- Aliases ---
test: run-test
tests: all-tests

# --- Clean ---
clean:
	@echo "Cleaning output directory..."
	rm -rf "$(OUT)"
