# Makefile for Paxos Council Election

JAVAC ?= javac
JAVA ?= java
SRC_DIR := src/main/java
PKG_DIR := $(SRC_DIR)/org
OUT_DIR := out
CLASSPATH := $(OUT_DIR)

# Test config
TEST_SRC_DIR := src/test/java
TEST_OUT := out_test
JUNIT_JAR := lib/junit-platform-console-standalone.jar

# Cross-platform classpath separator
SEP := :
ifeq ($(OS),Windows_NT)
  SEP := ;
endif

.PHONY: all build run member clean tests unit test

all: build

build:
	@mkdir -p $(OUT_DIR)
	$(JAVAC) -d $(OUT_DIR) $(PKG_DIR)/*.java

# Run a member: make member ID=M1 PROFILE=reliable CONFIG=network.config
member: build
	$(JAVA) -cp $(CLASSPATH) org.CouncilMember $(ID) --profile $(PROFILE) --config $(CONFIG)

tests: build
	bash run_tests.sh

# Compile unit tests
$(TEST_OUT): build
	@mkdir -p $(TEST_OUT)
	$(JAVAC) -cp "$(OUT_DIR)$(SEP)$(JUNIT_JAR)" -d $(TEST_OUT) $(shell find $(TEST_SRC_DIR) -name "*.java")

# Run unit tests via JUnit Console
unit: $(TEST_OUT)
	$(JAVA) -jar $(JUNIT_JAR) -cp "$(OUT_DIR)$(SEP)$(TEST_OUT)" --scan-classpath

test: unit

clean:
	rm -rf $(OUT_DIR) $(TEST_OUT) logs_M*.txt logs || true
