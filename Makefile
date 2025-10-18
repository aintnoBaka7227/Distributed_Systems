# Makefile for Paxos Council Election

JAVAC ?= javac
JAVA ?= java
SRC_DIR := src/main/java
PKG_DIR := $(SRC_DIR)/org
OUT_DIR := out
CLASSPATH := $(OUT_DIR)

.PHONY: all build run member clean tests

all: build

build:
	@mkdir -p $(OUT_DIR)
	$(JAVAC) -d $(OUT_DIR) $(PKG_DIR)/*.java

# Run a member: make member ID=M1 PROFILE=reliable CONFIG=network.config
member: build
	$(JAVA) -cp $(CLASSPATH) org.CouncilMember $(ID) --profile $(PROFILE) --config $(CONFIG)

tests: build
	bash run_tests.sh

clean:
	rm -rf $(OUT_DIR) logs_M*.txt logs || true

