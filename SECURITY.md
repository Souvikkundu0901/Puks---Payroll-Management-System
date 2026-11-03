# Security Policy — Puk's Payroll Management System

At **Puk's**, security, data integrity, and privacy are core architectural priorities. As a modern enterprise payroll and workforce management system dealing with sensitive personal and financial data, we maintain a proactive, defense-in-depth posture.

---

## 1. Supported Versions

Security updates and patches are actively maintained for the following versions:

| Version | Supported | Description |
| :--- | :--- | :--- |
| **0.0.1 (Current)** | :white_check_mark: | Active development & production-ready baseline |
| **< 0.0.1** | :x: | Legacy / unsupported iterations |

---

## 2. Reporting a Vulnerability

We deeply appreciate the efforts of security researchers and community contributors who responsibly report vulnerabilities.

If you believe you have found a security vulnerability in **Puk's Payroll Management System**, please follow our responsible disclosure guidelines:

- **Do NOT file a public issue or discussion.**
- **Contact:** Send an email directly to [security@puks.com](mailto:security@puks.com) (or via GitHub Private Vulnerability Reporting if enabled on the repository).
- **Include Details:**
  1. Detailed summary of the potential vulnerability.
  2. Step-by-step reproduction instructions or a minimal Proof of Concept (PoC).
  3. Affected component, endpoint, or role context (e.g. Employee, Manager, Admin).
  4. Suggested remediation or patch if available.

### Response Timelines (SLA)
- **Initial Acknowledgement:** Within **24–48 hours**
- **Assessment & Triage:** Within **7 business days**
- **Patch Deployment & Coordinated Disclosure:** Within **30 days** depending on severity

---

## 3. Security Architecture & Controls in Force

The system implements strict non-negotiable security safeguards across every tier:

### 3.1 Authentication & Session Integrity
- **JWT + HttpOnly Cookie Architecture:** Stateless short-lived access tokens paired with cryptographically random refresh tokens stored as hashed secrets.
- **Grace Window Rotation:** 20-second token rotation grace window to prevent parallel-tab logout races, with instant token-family revocation upon reuse detection.
- **BCrypt Strength 12:** Industry-standard password hashing with individual salt.
- **Account Lockout & Rate Limiting:** Automatic account lockout after consecutive invalid attempts and Bucket4j token bucket rate limiting on authentication and sensitive endpoints.
- **Token Versioning:** Instant revocation across active sessions upon role changes, offboarding, or password resets.

### 3.2 Authorization & Data Isolation
- **Role-Based Access Control (RBAC):** Server-side verification only. Client-sent role dropdowns or claims are strictly ignored.
- **Team-Scoped Ownership:** Strict cross-tenant and cross-team IDOR prevention. Every endpoint enforces `@PreAuthorize` alongside service-level entity ownership verification.
- **No Self-Approval:** Managers cannot approve their own payroll records, tasks, or leave allocations.

### 3.3 Data Protection at Rest & in Transit
- **AES-256-GCM Encryption:** Bank account numbers and payment details are encrypted at rest with authenticated encryption and masked in all UI responses (`XXXX-XXXX-1234`).
- **Append-Only Audit Trails:** Database-trigger enforced immutability on `audit_logs` prevents any `UPDATE` or `DELETE` operations, even by privileged administrators.
- **Input Sanitization & Magic-Byte Validation:** File uploads undergo strict content-type and magic-byte inspection (preventing disguised executables) and CSV exports sanitize formula injection vectors (`=`, `+`, `-`, `@`).

### 3.4 HTTP & Network Hardening
- **Strict Content Security Policy (CSP):** Enforces `'self'` origins, prohibits external inline injection, and disables framing (`frame-ancestors 'none'`).
- **Subresource Integrity (SRI):** Every external CDN dependency is validated via strict cryptographic hash integrity checks.
- **Actuator Lockdown:** Internal actuator metrics and diagnostic endpoints are restricted; only minimal health status is exposed.

---

## 4. Safe Harbor

We consider security research conducted under this policy to be:
- Authorized under relevant computer misuse laws.
- In good faith, and we will not pursue legal action against researchers who adhere to responsible disclosure and do not compromise user data or disrupt service availability.
