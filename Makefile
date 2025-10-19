# ==============================================================
# Makefile for Paxos Council Election (Linux Only)
# ==============================================================
# Requires:
#   - JDK 21+ with javac/java on PATH
#   - JUnit Console jar in lib/
# ==============================================================

# --- Compiler / Runtime ---
JAVAC := javac
JAVA  := java

# --- Source / Output directories ---
SRC_DIR := src/main/java
OUT_DIR := out
CLASSPATH := $(OUT_DIR)

# --- Unit tests ---
TEST_SRC_DIR := src/test/java
TEST_OUT := out_test
JUNIT_JAR := $(strip $(firstword $(wildcard lib/junit-platform-console-standalone*.jar)))
ifeq ($(JUNIT_JAR),)
  JUNIT_JAR := lib/junit-platform-console-standalone.jar
endif

# --- Default run profile/config ---
PROFILE ?= reliable
CONFIG  ?= network.config

# --- Linux path separator ---
SEP := :

.PHONY: all build member m1 m2 m3 m4 m5 m6 m7 m8 m9 \
        tests compile-tests unit test-all test-class test-method list-tests test clean

# ==============================================================
# BUILD
# ==============================================================
# Compile all Java source files
all: build

build:
	@echo "Compiling source files..."
	mkdir -p $(OUT_DIR)
	$(JAVAC) -d $(OUT_DIR) $(shell find $(SRC_DIR) -name "*.java")

# ==============================================================
# RUN MEMBERS
# ==============================================================
# Run a member manually:
#   make member ID=M4 PROFILE=reliable CONFIG=network.config
member: build
	$(JAVA) -cp "$(CLASSPATH)" org.CouncilMember $(ID) --profile $(PROFILE) --config $(CONFIG)

# Fast per-member shortcuts (Linux only)
m1: build ; $(JAVA) -cp "$(CLASSPATH)" org.CouncilMember M1 --profile $(PROFILE) --config $(CONFIG)
m2: build ; $(JAVA) -cp "$(CLASSPATH)" org.CouncilMember M2 --profile $(PROFILE) --config $(CONFIG)
m3: build ; $(JAVA) -cp "$(CLASSPATH)" org.CouncilMember M3 --profile $(PROFILE) --config $(CONFIG)
m4: build ; $(JAVA) -cp "$(CLASSPATH)" org.CouncilMember M4 --profile $(PROFILE) --config $(CONFIG)
m5: build ; $(JAVA) -cp "$(CLASSPATH)" org.CouncilMember M5 --profile $(PROFILE) --config $(CONFIG)
m6: build ; $(JAVA) -cp "$(CLASSPATH)" org.CouncilMember M6 --profile $(PROFILE) --config $(CONFIG)
m7: build ; $(JAVA) -cp "$(CLASSPATH)" org.CouncilMember M7 --profile $(PROFILE) --config $(CONFIG)
m8: build ; $(JAVA) -cp "$(CLASSPATH)" org.CouncilMember M8 --profile $(PROFILE) --config $(CONFIG)
m9: build ; $(JAVA) -cp "$(CLASSPATH)" org.CouncilMember M9 --profile $(PROFILE) --config $(CONFIG)

# ==============================================================
# INTEGRATION TESTS (run_tests.sh)
# ==============================================================
tests: build
	bash run_tests.sh

# ==============================================================
# UNIT TESTS (JUnit 5 Console)
# ==============================================================
# Compile all test classes
compile-tests: build
	@echo "Compiling unit tests..."
	mkdir -p $(TEST_OUT)
	$(JAVAC) -cp "$(OUT_DIR)$(SEP)$(JUNIT_JAR)" -d $(TEST_OUT) $(shell find $(TEST_SRC_DIR) -name "*.java")

# Run all tests
unit test-all: compile-tests
	@echo "Running all tests..."
	$(JAVA) -jar "$(JUNIT_JAR)" -cp "$(OUT_DIR)$(SEP)$(TEST_OUT)" --scan-classpath

# Run a single test class
#   make test-class TEST=org.ProposerTest
test-class: compile-tests
	$(JAVA) -jar "$(JUNIT_JAR)" -cp "$(OUT_DIR)$(SEP)$(TEST_OUT)" --select-class="$(TEST)"

# List all available test classes
list-tests:
	@echo "Available test classes:"
	@find "$(TEST_SRC_DIR)" -name '*Test.java' \
	  | sed -e 's#^$(TEST_SRC_DIR)/##' -e 's#/#.#g' -e 's#\.java$$##'

# Shortcut alias
test: unit

# ==============================================================
# CLEAN
# ==============================================================
clean:
	@echo "Cleaning build a
