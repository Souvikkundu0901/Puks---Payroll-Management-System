// Authentication & Role-based Guard for Octopyder Payroll
const Auth = (function () {
    let currentUser = null;

    function getDashboardUrl(role) {
        const isSubfolder = window.location.pathname.includes('/admin/') || 
                            window.location.pathname.includes('/manager/') || 
                            window.location.pathname.includes('/employee/');
        const prefix = isSubfolder ? '../' : '';
        switch (role) {
            case 'ADMIN':
                return `${prefix}admin/dashboard.html`;
            case 'MANAGER':
                return `${prefix}manager/dashboard.html`;
            case 'EMPLOYEE':
                return `${prefix}employee/dashboard.html`;
            default:
                return `${prefix}index.html`;
        }
    }

    function getLoginUrl() {
        const isSubfolder = window.location.pathname.includes('/admin/') || 
                            window.location.pathname.includes('/manager/') || 
                            window.location.pathname.includes('/employee/');
        return isSubfolder ? '../index.html' : 'index.html';
    }

    async function initUser() {
        if (currentUser) {
            return currentUser;
        }
        // Attempt refresh to hydrate access token in memory
        const refreshData = await API.refreshToken();
        if (!refreshData) {
            return null;
        }
        try {
            currentUser = await API.get('/auth/me');
            return currentUser;
        } catch (e) {
            currentUser = null;
            return null;
        }
    }

    async function require(expectedRole) {
        const user = await initUser();
        if (!user) {
            window.location.href = getLoginUrl();
            return null;
        }

        const isAllowed = Array.isArray(expectedRole)
            ? expectedRole.includes(user.role)
            : user.role === expectedRole;

        if (!isAllowed) {
            window.location.href = getDashboardUrl(user.role);
            return null;
        }

        // Apply theme from user preference if present
        if (user.theme && !localStorage.getItem('payrollTheme')) {
            localStorage.setItem('payrollTheme', user.theme);
            if (user.theme !== 'default') {
                document.body.classList.add(user.theme);
            }
        }

        attachUserInfo(user);
        return user;
    }

    function attachUserInfo(user) {
        const run = () => {
            const userNameEls = document.querySelectorAll('.user-name, [data-user-name]');
            userNameEls.forEach(el => { el.textContent = user.name || user.code; });
            const userRoleEls = document.querySelectorAll('.user-role, [data-user-role]');
            userRoleEls.forEach(el => { el.textContent = user.role; });
            const userEmailEls = document.querySelectorAll('.user-email, [data-user-email]');
            userEmailEls.forEach(el => { el.textContent = user.email; });

            // Wire logout triggers
            document.querySelectorAll('a[title="Logout"], .btn-logout, [data-action="logout"], a[href*="index.html"]').forEach(btn => {
                if (btn.querySelector('.bi-box-arrow-right') || btn.textContent.toLowerCase().includes('logout') || btn.title === 'Logout') {
                    btn.addEventListener('click', (e) => {
                        e.preventDefault();
                        logout();
                    });
                }
            });
        };

        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', run);
        } else {
            run();
        }
    }

    async function login(email, password, rememberMe) {
        const data = await API.post('/auth/login', { email, password, rememberMe });
        API.setToken(data.accessToken);
        currentUser = data.user;
        return data;
    }

    async function logout() {
        try {
            await API.post('/auth/logout', {});
        } catch (e) {
            // Ignore error
        } finally {
            API.clearToken();
            currentUser = null;
            window.location.href = getLoginUrl();
        }
    }

    async function changePassword(currentPassword, newPassword) {
        return await API.post('/auth/change-password', { currentPassword, newPassword });
    }

    function getUser() {
        return currentUser;
    }

    return {
        require,
        login,
        logout,
        changePassword,
        getUser,
        getDashboardUrl,
        initUser,
        getToken: () => (window.API ? API.getToken() : null)
    };
})();
