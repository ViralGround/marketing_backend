#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../lib/http_contract.sh
source "${SCRIPT_DIR}/../lib/http_contract.sh"

# Git Bash can resolve the Windows Store `python3` launcher even when it is not
# executable. Keep production defaulting to python3, but make this local fixture
# use the installed `python` binary when that launcher is only a stub.
if ! python3 --version >/dev/null 2>&1 && python --version >/dev/null 2>&1; then
  export DBOPS_PYTHON_BIN=python
fi

fixture_root="$(mktemp -d)"
cleanup() {
  if [[ "$fixture_root" == /tmp/* || "$fixture_root" == "${TMPDIR:-/tmp}"/* ]]; then
    rm -rf -- "$fixture_root"
  fi
}
trap cleanup EXIT

landing_shape="$(printf 'a%.0s' {1..64})"
changed_landing_shape="$(printf 'b%.0s' {1..64})"
invalid_login_shape="$(printf 'c%.0s' {1..64})"
success_login_shape="$(printf 'd%.0s' {1..64})"
refresh_shape="$(printf 'e%.0s' {1..64})"

cat >"${fixture_root}/matched.tsv" <<EOF
rc	started-hibernate-validated-readonly-temp-auth	landing:200:${landing_shape}	loginInvalid:401:${invalid_login_shape}	loginSuccess:200:${success_login_shape}	refreshSuccess:204:${refresh_shape}
legacy	started-hibernate-validated-readonly-temp-auth	landing:200:${landing_shape}	loginInvalid:401:${invalid_login_shape}	loginSuccess:200:${success_login_shape}	refreshSuccess:204:${refresh_shape}
EOF
compare_http_contract_evidence \
  "${fixture_root}/matched.tsv" "${fixture_root}/matched-comparison.tsv"
[[ "$(grep -c $'\tMATCHED$' "${fixture_root}/matched-comparison.tsv")" == "4" ]]

cat >"${fixture_root}/mismatch.tsv" <<EOF
rc	started-hibernate-validated-readonly-temp-auth	landing:200:${changed_landing_shape}	loginInvalid:401:${invalid_login_shape}	loginSuccess:200:${success_login_shape}	refreshSuccess:204:${refresh_shape}
legacy	started-hibernate-validated-readonly-temp-auth	landing:200:${landing_shape}	loginInvalid:401:${invalid_login_shape}	loginSuccess:200:${success_login_shape}	refreshSuccess:204:${refresh_shape}
EOF
set +e
comparison_error="$(compare_http_contract_evidence \
  "${fixture_root}/mismatch.tsv" "${fixture_root}/mismatch-comparison.tsv" 2>&1)"
comparison_status=$?
set -e
[[ "$comparison_status" != "0" ]]
grep -Fq 'RC/legacy HTTP contract mismatch: landing' <<<"$comparison_error"
grep -Fq $'landing\t200\t' "${fixture_root}/mismatch-comparison.tsv"
grep -Fq $'\tMISMATCH' "${fixture_root}/mismatch-comparison.tsv"

cat >"${fixture_root}/missing-legacy.tsv" <<EOF
rc	started-hibernate-validated-readonly-temp-auth	landing:200:${landing_shape}	loginInvalid:401:${invalid_login_shape}	loginSuccess:200:${success_login_shape}	refreshSuccess:204:${refresh_shape}
EOF
set +e
missing_error="$(compare_http_contract_evidence \
  "${fixture_root}/missing-legacy.tsv" "${fixture_root}/missing-comparison.tsv" 2>&1)"
missing_status=$?
set -e
[[ "$missing_status" != "0" ]]
grep -Fq 'both rc and legacy HTTP contract rows are required' <<<"$missing_error"

cat >"${fixture_root}/legacy-generic.tsv" <<EOF
rc	started-hibernate-validated-readonly	landing:200:${landing_shape}	login:401:${invalid_login_shape}
legacy	started-hibernate-validated-readonly	landing:200:${landing_shape}	login:401:${invalid_login_shape}
EOF
set +e
generic_error="$(compare_http_contract_evidence \
  "${fixture_root}/legacy-generic.tsv" "${fixture_root}/legacy-generic-comparison.tsv" 2>&1)"
generic_status=$?
set -e
[[ "$generic_status" != "0" ]]
grep -Fq 'malformed HTTP contract row' <<<"$generic_error"

printf 'RC/legacy HTTP contract comparison fixture passed.\n'
