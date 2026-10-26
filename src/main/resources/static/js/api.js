// API fetch wrapper for Octopyder Payroll
const API = (function () {
    let accessToken = null;
    let refreshPromise = null;

    function getBaseUrl() {
        return '/api/v1';
    }

    function setToken(token) {
        accessToken = token;
    }

    function getToken() {
        return accessToken;
    }

    function clearToken() {
        accessToken = null;
    }

    async function refreshToken() {
        if (refreshPromise) {
            return refreshPromise;
        }
        refreshPromise = (async () => {
            try {
                const res = await fetch(`${getBaseUrl()}/auth/refresh`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    credentials: 'same-origin'
                });
                if (!res.ok) {
                    clearToken();
                    return null;
                }
                const data = await res.json();
                setToken(data.accessToken);
                return data;
            } catch (e) {
                clearToken();
                return null;
            } finally {
                refreshPromise = null;
            }
        })();
        return refreshPromise;
    }

    async function request(endpoint, options = {}) {
        const url = endpoint.startsWith('http') ? endpoint : `${getBaseUrl()}${endpoint.startsWith('/') ? '' : '/'}${endpoint}`;
        const headers = options.headers ? { ...options.headers } : {};

        if (accessToken) {
            headers['Authorization'] = `Bearer ${accessToken}`;
        }
        if (!(options.body instanceof FormData) && !headers['Content-Type'] && options.method && options.method !== 'GET') {
            headers['Content-Type'] = 'application/json';
        }

        const config = {
            ...options,
            headers,
            credentials: 'same-origin'
        };

        let response = await fetch(url, config);

        // If 401 and not already an auth endpoint, attempt refresh once
        if (response.status === 401 && !endpoint.includes('/auth/login') && !endpoint.includes('/auth/refresh')) {
            const refreshRes = await refreshToken();
            if (refreshRes && refreshRes.accessToken) {
                headers['Authorization'] = `Bearer ${refreshRes.accessToken}`;
                response = await fetch(url, { ...config, headers });
            } else {
                clearToken();
                if (!window.location.pathname.endsWith('index.html') && window.location.pathname !== '/') {
                    window.location.href = '/index.html';
                }
            }
        }

        if (!response.ok) {
            let errorBody;
            try {
                errorBody = await response.json();
            } catch (e) {
                errorBody = {
                    status: response.status,
                    title: response.statusText,
                    detail: 'An unexpected error occurred.'
                };
            }
            const err = new Error(errorBody.detail || errorBody.title || 'API Error');
            err.status = response.status;
            err.problem = errorBody;
            throw err;
        }

        if (response.status === 204) {
            return null;
        }

        const contentType = response.headers.get('content-type');
        if (contentType && contentType.includes('application/json')) {
            return await response.json();
        }
        return await response.text();
    }

    return {
        request,
        get: (url, opts) => request(url, { ...opts, method: 'GET' }),
        post: (url, body, opts) => request(url, { ...opts, method: 'POST', body: body instanceof FormData ? body : JSON.stringify(body) }),
        put: (url, body, opts) => request(url, { ...opts, method: 'PUT', body: body instanceof FormData ? body : JSON.stringify(body) }),
        delete: (url, opts) => request(url, { ...opts, method: 'DELETE' }),
        setToken,
        getToken,
        clearToken,
        refreshToken
    };
})();
