HOLDER_BACKEND:=~/Documents/DEV/LIST/EBSILUX/local-prod/holder/waltid-wallet-backend
ISSUER_BACKEND:=~/Documents/DEV/LIST/EBSILUX/local-prod/issuer/waltid-wallet-backend
VERIFIER_BACKEND:=~/Documents/DEV/LIST/EBSILUX/local-prod/verifier/waltid-wallet-backend
BACKEND_DIST:=./build/install/waltid-wallet-backend

.PHONY: deploy-local-holder
deploy-local-holder:
	LIST_SSL_ENABLED=false; \
	WALTID_DATA_ROOT=/Users/nicolas/Documents/DEV/LIST/EBSILUX/local-prod/holder/holder-data; \
	export LIST_SSL_ENABLED; \
	export WALTID_DATA_ROOT; \
	echo $$WALTID_DATA_ROOT; cd $(HOLDER_BACKEND) && bin/waltid-wallet-backend run -p 8080

.PHONY: deploy-local-issuer
deploy-local-issuer:
	LIST_SSL_ENABLED=false; \
	WALTID_DATA_ROOT=/Users/nicolas/Documents/DEV/LIST/EBSILUX/local-prod/issuer/issuer-data; \
	export LIST_SSL_ENABLED; \
	export WALTID_DATA_ROOT; \
	echo $$WALTID_DATA_ROOT; cd $(ISSUER_BACKEND) && bin/waltid-wallet-backend run -p 9000

.PHONY: deploy-local-verifier
deploy-local-verifier:
	LIST_SSL_ENABLED=false; \
	WALTID_DATA_ROOT=/Users/nicolas/Documents/DEV/LIST/EBSILUX/local-prod/verifier/issuer-data; \
	export LIST_SSL_ENABLED; \
	export WALTID_DATA_ROOT; \
	echo $$WALTID_DATA_ROOT; cd $(VERIFIER_BACKEND) && bin/waltid-wallet-backend run -p 9200

.PHONY: build
build:
	sh gradlew clean ; sh gradlew jar

.PHONY: dist
dist:
	sh gradlew clean ; sh gradlew installDist

.PHONY: cp_2_holder
cp_2_holder:
	cp -Rf $(BACKEND_DIST)/bin $(HOLDER_BACKEND)/bin
	cp -Rf $(BACKEND_DIST)/lib $(HOLDER_BACKEND)/lib

.PHONY: cp_2_issuer
cp_2_issuer:
	cp -Rf $(BACKEND_DIST)/bin $(ISSUER_BACKEND)/bin
	cp -Rf $(BACKEND_DIST)/lib $(ISSUER_BACKEND)/lib

.PHONY: cp_2_verifier
cp_2_verifier:
	cp -Rf $(BACKEND_DIST)/bin $(VERIFIER_BACKEND)/bin
	cp -Rf $(BACKEND_DIST)/lib $(VERIFIER_BACKEND)/lib

.PHONY: run_holder_backend
run_holder_backend: dist cp_2_holder deploy-local-holder
	echo "Build and deploy HOLDER backend"

.PHONY: run_issuer_backend
run_issuer_backend: dist cp_2_issuer deploy-local-issuer
	echo "Build and deploy ISSUER backend"

.PHONY: run_verifier_backend
run_verifier_backend: dist cp_2_issuer deploy-local-verifier
	echo "Build and deploy VERIFIER backend"