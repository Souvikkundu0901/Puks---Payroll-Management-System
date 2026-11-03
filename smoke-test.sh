#!/usr/bin/env bash
# Smoke test script for Octopyder Payroll
# Exits non-zero on any failure. Never logs secrets or tokens.
set -euo pipefail

BASE_URL="${APP_BASE_URL:-http://localhost:8080}"
API_URL="${BASE_URL}/api/v1"

pass_count=0
fail_count=0

pass() {
    echo "[PASS] $1"
    pass_count=$((pass_count + 1))
}

fail() {
    echo "[FAIL] $1" >&2
    fail_count=$((fail_count + 1))
    exit 1
}

# Temporary files cleanup on exit
TMP_DIR=$(mktemp -d)
cleanup() {
    rm -rf "$TMP_DIR"
}
trap cleanup EXIT

echo "========================================="
echo " Starting Octopyder Payroll Smoke Tests"
echo " Target: ${BASE_URL}"
echo "========================================="

# 1. Health check (Lockdown: UP and zero health details)
HEALTH_OUT="$TMP_DIR/health.json"
HTTP_CODE=$(curl -s -o "$HEALTH_OUT" -w "%{http_code}" "${BASE_URL}/actuator/health")
if [[ "$HTTP_CODE" -eq 200 ]]; then
    STATUS=$(jq -r '.status // empty' "$HEALTH_OUT" 2>/dev/null || true)
    COMPONENTS=$(jq -r '.components // empty' "$HEALTH_OUT" 2>/dev/null || true)
    DETAILS=$(jq -r '.details // empty' "$HEALTH_OUT" 2>/dev/null || true)
    if [[ "$STATUS" == "UP" && -z "$COMPONENTS" && -z "$DETAILS" ]]; then
        pass "Actuator health check returns UP with zero details"
    else
        fail "Actuator health leaked details or unexpected status: $(cat "$HEALTH_OUT")"
    fi
else
    fail "Actuator health check failed with HTTP ${HTTP_CODE}"
fi

# 2. Admin Login
ADMIN_LOGIN_OUT="$TMP_DIR/admin_login.json"
ADMIN_HTTP=$(curl -s -o "$ADMIN_LOGIN_OUT" -w "%{http_code}" \
    -H "Content-Type: application/json" \
    -d '{"email":"admin@octopyder.com","password":"AdminBootstrap123!"}' \
    "${API_URL}/auth/login")

if [[ "$ADMIN_HTTP" -eq 200 ]]; then
    ADMIN_TOKEN=$(jq -r '.accessToken // empty' "$ADMIN_LOGIN_OUT")
    if [[ -n "$ADMIN_TOKEN" ]]; then
        pass "Admin authenticated successfully"
    else
        fail "Admin login succeeded but accessToken missing"
    fi
else
    fail "Admin login failed with HTTP ${ADMIN_HTTP}"
fi

# 3. Manager Login
MGR_LOGIN_OUT="$TMP_DIR/mgr_login.json"
MGR_HTTP=$(curl -s -o "$MGR_LOGIN_OUT" -w "%{http_code}" \
    -H "Content-Type: application/json" \
    -d '{"email":"manager@octopyder.com","password":"Manager123!"}' \
    "${API_URL}/auth/login")

if [[ "$MGR_HTTP" -eq 200 ]]; then
    MGR_TOKEN=$(jq -r '.accessToken // empty' "$MGR_LOGIN_OUT")
    if [[ -n "$MGR_TOKEN" ]]; then
        pass "Manager authenticated successfully"
    else
        fail "Manager login succeeded but accessToken missing"
    fi
else
    fail "Manager login failed with HTTP ${MGR_HTTP}"
fi

# 4. Employee Login
EMP_LOGIN_OUT="$TMP_DIR/emp_login.json"
EMP_HTTP=$(curl -s -o "$EMP_LOGIN_OUT" -w "%{http_code}" \
    -H "Content-Type: application/json" \
    -d '{"email":"ananya.sharma@octopyder.com","password":"Employee123!"}' \
    "${API_URL}/auth/login")

if [[ "$EMP_HTTP" -eq 200 ]]; then
    EMP_TOKEN=$(jq -r '.accessToken // empty' "$EMP_LOGIN_OUT")
    EMP_USER_ID=$(jq -r '.user.id // empty' "$EMP_LOGIN_OUT")
    if [[ -n "$EMP_TOKEN" ]]; then
        pass "Employee authenticated successfully"
    else
        fail "Employee login succeeded but accessToken missing"
    fi
else
    fail "Employee login failed with HTTP ${EMP_HTTP}"
fi

# 5. Manager Dashboard / Review check
MGR_REVIEW_OUT="$TMP_DIR/mgr_review.json"
MGR_REVIEW_HTTP=$(curl -s -o "$MGR_REVIEW_OUT" -w "%{http_code}" \
    -H "Authorization: Bearer ${MGR_TOKEN}" \
    "${API_URL}/leave/requests?status=PENDING")
if [[ "$MGR_REVIEW_HTTP" -eq 200 ]]; then
    pass "Manager pending leave endpoint accessible"
else
    fail "Manager pending leave endpoint returned HTTP ${MGR_REVIEW_HTTP}"
fi

# 6. Employee Today Attendance
EMP_ATT_OUT="$TMP_DIR/emp_att.json"
EMP_ATT_HTTP=$(curl -s -o "$EMP_ATT_OUT" -w "%{http_code}" \
    -H "Authorization: Bearer ${EMP_TOKEN}" \
    "${API_URL}/attendance/me/today")
if [[ "$EMP_ATT_HTTP" -eq 200 ]]; then
    pass "Employee attendance today endpoint accessible"
else
    fail "Employee attendance today endpoint returned HTTP ${EMP_ATT_HTTP}"
fi

# 7. Employee Leave Balance
EMP_LEAVE_OUT="$TMP_DIR/emp_leave.json"
EMP_LEAVE_HTTP=$(curl -s -o "$EMP_LEAVE_OUT" -w "%{http_code}" \
    -H "Authorization: Bearer ${EMP_TOKEN}" \
    "${API_URL}/leave/me/balance")
if [[ "$EMP_LEAVE_HTTP" -eq 200 ]]; then
    pass "Employee leave balance endpoint accessible"
else
    fail "Employee leave balance endpoint returned HTTP ${EMP_LEAVE_HTTP}"
fi

# 8. Notifications Unread Count
NOTIF_OUT="$TMP_DIR/notif.json"
NOTIF_HTTP=$(curl -s -o "$NOTIF_OUT" -w "%{http_code}" \
    -H "Authorization: Bearer ${EMP_TOKEN}" \
    "${API_URL}/notifications/unread-count")
if [[ "$NOTIF_HTTP" -eq 200 ]]; then
    pass "Notifications unread-count endpoint accessible"
else
    fail "Notifications endpoint returned HTTP ${NOTIF_HTTP}"
fi

# 9. Create Employee + Forced Password Change Flow
DEPTS_OUT="$TMP_DIR/depts.json"
curl -s -H "Authorization: Bearer ${ADMIN_TOKEN}" "${API_URL}/departments" -o "$DEPTS_OUT"
ENG_DEPT_ID=$(jq -r '.[0].id // empty' "$DEPTS_OUT")
if [[ -z "$ENG_DEPT_ID" ]]; then
    fail "Could not fetch department ID for employee creation"
fi

RANDOM_SUFFIX=$((RANDOM % 90000 + 10000))
NEW_EMP_EMAIL="smoketest_${RANDOM_SUFFIX}@example.com"
CREATE_PAYLOAD=$(cat <<EOF
{
  "firstName": "Smoke",
  "lastName": "User",
  "email": "${NEW_EMP_EMAIL}",
  "phone": "+91 91234 56789",
  "departmentId": "${ENG_DEPT_ID}",
  "designation": "QA Analyst",
  "employmentType": "FULL_TIME",
  "joiningDate": "2026-01-01",
  "baseSalary": 50000.00,
  "experienceYears": 2.0,
  "skills": ["Automation"],
  "role": "EMPLOYEE"
}
EOF
)

CREATE_OUT="$TMP_DIR/new_emp.json"
CREATE_HTTP=$(curl -s -o "$CREATE_OUT" -w "%{http_code}" \
    -H "Authorization: Bearer ${ADMIN_TOKEN}" \
    -H "Content-Type: application/json" \
    -d "$CREATE_PAYLOAD" \
    "${API_URL}/employees")

if [[ "$CREATE_HTTP" -eq 201 ]]; then
    TEMP_PWD=$(jq -r '.temporaryPassword // .tempPassword // empty' "$CREATE_OUT")
    NEW_USER_ID=$(jq -r '.id // empty' "$CREATE_OUT")
    if [[ -n "$TEMP_PWD" ]]; then
        pass "Employee created with temporary password"
    else
        fail "Employee creation succeeded but tempPassword missing"
    fi
else
    fail "Employee creation failed with HTTP ${CREATE_HTTP}"
fi

# Login with temporary password - must require password change
TEMP_LOGIN_OUT="$TMP_DIR/temp_login.json"
TEMP_LOGIN_HTTP=$(curl -s -o "$TEMP_LOGIN_OUT" -w "%{http_code}" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"${NEW_EMP_EMAIL}\",\"password\":\"${TEMP_PWD}\"}" \
    "${API_URL}/auth/login")

if [[ "$TEMP_LOGIN_HTTP" -eq 200 ]]; then
    MUST_CHANGE=$(jq -r '.user.mustChangePassword' "$TEMP_LOGIN_OUT")
    TEMP_TOKEN=$(jq -r '.accessToken // empty' "$TEMP_LOGIN_OUT")
    if [[ "$MUST_CHANGE" == "true" ]]; then
        pass "Login with temp password enforced mustChangePassword=true"
    else
        fail "Login with temp password did not require password change"
    fi
else
    fail "Login with temporary password failed with HTTP ${TEMP_LOGIN_HTTP}"
fi

# Change password
PERMANENT_PWD="NewStrongPassword123!"
CHG_OUT="$TMP_DIR/change_pwd.json"
CHG_HTTP=$(curl -s -o "$CHG_OUT" -w "%{http_code}" \
    -H "Authorization: Bearer ${TEMP_TOKEN}" \
    -H "Content-Type: application/json" \
    -d "{\"currentPassword\":\"${TEMP_PWD}\",\"newPassword\":\"${PERMANENT_PWD}\"}" \
    "${API_URL}/auth/change-password")

if [[ "$CHG_HTTP" -eq 200 || "$CHG_HTTP" -eq 204 ]]; then
    pass "Password changed successfully"
else
    fail "Password change failed with HTTP ${CHG_HTTP}"
fi

# Login with new permanent password
NEW_LOGIN_OUT="$TMP_DIR/new_login.json"
NEW_LOGIN_HTTP=$(curl -s -o "$NEW_LOGIN_OUT" -w "%{http_code}" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"${NEW_EMP_EMAIL}\",\"password\":\"${PERMANENT_PWD}\"}" \
    "${API_URL}/auth/login")

if [[ "$NEW_LOGIN_HTTP" -eq 200 ]]; then
    MUST_CHANGE_AFTER=$(jq -r '.user.mustChangePassword' "$NEW_LOGIN_OUT")
    if [[ "$MUST_CHANGE_AFTER" == "false" ]]; then
        pass "Login with updated password succeeded with mustChangePassword=false"
    else
        fail "Updated password login still required password change"
    fi
else
    fail "Login with updated password failed with HTTP ${NEW_LOGIN_HTTP}"
fi

# 10. Payslip PDF Download & Validation
PAYROLL_LIST_OUT="$TMP_DIR/payroll_records.json"
curl -s -H "Authorization: Bearer ${ADMIN_TOKEN}" "${API_URL}/payroll/records?page=0&size=10" -o "$PAYROLL_LIST_OUT"
FIRST_RECORD_ID=$(jq -r '.items[0].id // empty' "$PAYROLL_LIST_OUT")

if [[ -z "$FIRST_RECORD_ID" ]]; then
    # Auto-generate a run for test if no records exist yet
    curl -s -H "Authorization: Bearer ${ADMIN_TOKEN}" \
        -H "Content-Type: application/json" \
        -d '{"month":"2026-07"}' \
        "${API_URL}/payroll/runs" >/dev/null || true
    curl -s -H "Authorization: Bearer ${ADMIN_TOKEN}" "${API_URL}/payroll/records?page=0&size=10" -o "$PAYROLL_LIST_OUT"
    FIRST_RECORD_ID=$(jq -r '.items[0].id // empty' "$PAYROLL_LIST_OUT")
fi

if [[ -n "$FIRST_RECORD_ID" ]]; then
    PAYSLIP_PDF="$TMP_DIR/payslip.pdf"
    PDF_HTTP=$(curl -s -o "$PAYSLIP_PDF" -w "%{http_code}" \
        -H "Authorization: Bearer ${ADMIN_TOKEN}" \
        "${API_URL}/payroll/records/${FIRST_RECORD_ID}/payslip.pdf")

    if [[ "$PDF_HTTP" -eq 200 ]]; then
        HEADER=$(head -c 4 "$PAYSLIP_PDF")
        if [[ "$HEADER" == "%PDF" ]]; then
            pass "Payslip PDF downloaded and validated with %PDF header"
        else
            fail "Payslip downloaded but header does not start with %PDF: ${HEADER}"
        fi
    else
        fail "Payslip download failed with HTTP ${PDF_HTTP}"
    fi
else
    fail "No payroll record found to test payslip PDF download"
fi

# 11. Payroll Export
EXPORT_CSV="$TMP_DIR/payroll_export.csv"
PAST_MONTH=$(date +"%Y-%m" -d "last month" 2>/dev/null || date +"%Y-%m")
EXPORT_HTTP=$(curl -s -o "$EXPORT_CSV" -w "%{http_code}" \
    -H "Authorization: Bearer ${ADMIN_TOKEN}" \
    "${API_URL}/payroll/export?format=csv&month=${PAST_MONTH}")

if [[ "$EXPORT_HTTP" -eq 200 ]]; then
    LINE_COUNT=$(wc -l < "$EXPORT_CSV")
    if [[ "$LINE_COUNT" -gt 0 ]]; then
        pass "Payroll export downloaded successfully (${LINE_COUNT} lines)"
    else
        fail "Payroll export returned empty content"
    fi
else
    fail "Payroll export failed with HTTP ${EXPORT_HTTP}"
fi

# 12. Cross-user Payslip Isolation (403 or 404)
# Employee attempts to download another employee's payslip
OTHER_RECORD_ID=$(jq -r '.items[] | select(.userId != "'"${EMP_USER_ID}"'") | .id' "$PAYROLL_LIST_OUT" | head -n 1)
if [[ -z "$OTHER_RECORD_ID" ]]; then
    OTHER_RECORD_ID="$FIRST_RECORD_ID"
fi

CROSS_PAYSLIP_OUT="$TMP_DIR/cross_payslip.json"
CROSS_PAYSLIP_HTTP=$(curl -s -o "$CROSS_PAYSLIP_OUT" -w "%{http_code}" \
    -H "Authorization: Bearer ${EMP_TOKEN}" \
    "${API_URL}/payroll/records/${OTHER_RECORD_ID}/payslip.pdf")

if [[ "$CROSS_PAYSLIP_HTTP" -eq 403 || "$CROSS_PAYSLIP_HTTP" -eq 404 ]]; then
    pass "Cross-user payslip download correctly rejected with HTTP ${CROSS_PAYSLIP_HTTP}"
else
    fail "Cross-user payslip download was NOT rejected: returned HTTP ${CROSS_PAYSLIP_HTTP}"
fi

# 13. Cross-user Bank-detail Isolation (403 or 404)
# Employee attempts to view another user's bank account
CROSS_BANK_OUT="$TMP_DIR/cross_bank.json"
CROSS_BANK_HTTP=$(curl -s -o "$CROSS_BANK_OUT" -w "%{http_code}" \
    -H "Authorization: Bearer ${EMP_TOKEN}" \
    "${API_URL}/employees/${NEW_USER_ID}/bank-account")

if [[ "$CROSS_BANK_HTTP" -eq 403 || "$CROSS_BANK_HTTP" -eq 404 ]]; then
    pass "Cross-user bank details access correctly rejected with HTTP ${CROSS_BANK_HTTP}"
else
    fail "Cross-user bank details access was NOT rejected: returned HTTP ${CROSS_BANK_HTTP}"
fi

echo "========================================="
echo " All smoke tests passed successfully! (${pass_count} checks)"
echo "========================================="
exit 0
