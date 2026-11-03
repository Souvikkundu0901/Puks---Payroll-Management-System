// API fetch wrapper for Octopyder Payroll
const API = (function () {
    let accessToken = null;
    let refreshPromise = null;

    function getBaseUrl() {
        return '/api/v1';
    }

    function setToken(token) {
        accessToken = token;
        if (token) {
            startNotificationPolling();
        } else {
            stopNotificationPolling();
        }
    }

    function getToken() {
        return accessToken;
    }

    function clearToken() {
        accessToken = null;
        stopNotificationPolling();
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

    // Notification Poller & Bell Wire
    let notifInterval = null;
    let isPolling = false;

    async function updateUnreadCount() {
        if (!accessToken || document.hidden) return;
        try {
            const data = await request('/notifications/unread-count', { method: 'GET' });
            const count = data?.count || 0;
            const badges = document.querySelectorAll('.notification-badge, #notif-count');
            badges.forEach(b => {
                b.textContent = count;
                b.style.display = count > 0 ? '' : 'none';
            });
        } catch (e) {
            if (e.status === 401) {
                stopNotificationPolling();
            }
        }
    }

    function startNotificationPolling() {
        if (isPolling) return;
        isPolling = true;
        updateUnreadCount();
        if (notifInterval) clearInterval(notifInterval);
        notifInterval = setInterval(() => {
            if (!document.hidden) {
                updateUnreadCount();
            }
        }, 60000);
    }

    function stopNotificationPolling() {
        if (notifInterval) {
            clearInterval(notifInterval);
            notifInterval = null;
        }
        isPolling = false;
    }

    async function loadNotificationDropdown(dropdownMenu) {
        if (!dropdownMenu) return;
        try {
            const list = await request('/notifications?unreadOnly=false', { method: 'GET' });
            dropdownMenu.replaceChildren();

            const header = document.createElement('li');
            const h6 = document.createElement('h6');
            h6.className = 'dropdown-header d-flex justify-content-between align-items-center';
            h6.textContent = 'Notifications';

            const markAllBtn = document.createElement('button');
            markAllBtn.className = 'btn btn-link btn-sm text-decoration-none p-0';
            markAllBtn.style.fontSize = '0.75rem';
            markAllBtn.textContent = 'Mark all read';
            markAllBtn.addEventListener('click', async (e) => {
                e.stopPropagation();
                try {
                    await request('/notifications/read-all', { method: 'POST' });
                    updateUnreadCount();
                    loadNotificationDropdown(dropdownMenu);
                } catch (err) {}
            });
            h6.appendChild(markAllBtn);
            header.appendChild(h6);
            dropdownMenu.appendChild(header);

            if (!list || list.length === 0) {
                const emptyLi = document.createElement('li');
                emptyLi.className = 'dropdown-item text-muted text-center small py-3';
                emptyLi.textContent = 'No notifications';
                dropdownMenu.appendChild(emptyLi);
                return;
            }

            const itemsToShow = list.slice(0, 10);
            itemsToShow.forEach(n => {
                const li = document.createElement('li');
                const a = document.createElement('a');
                a.className = 'dropdown-item small text-wrap py-2 ' + (n.readAt ? 'text-muted' : 'fw-bold text-dark');
                a.href = n.link || '#';

                const titleSpan = document.createElement('div');
                titleSpan.textContent = n.title;
                a.appendChild(titleSpan);

                if (n.body) {
                    const bodySpan = document.createElement('small');
                    bodySpan.className = 'text-muted d-block';
                    bodySpan.textContent = n.body;
                    a.appendChild(bodySpan);
                }

                a.addEventListener('click', async () => {
                    if (!n.readAt) {
                        try {
                            await request(`/notifications/${n.id}/read`, { method: 'POST' });
                            updateUnreadCount();
                        } catch (err) {}
                    }
                });

                li.appendChild(a);
                dropdownMenu.appendChild(li);
            });

            if (list.length > 10) {
                const footerLi = document.createElement('li');
                footerLi.className = 'dropdown-item text-center small text-primary py-2';
                footerLi.style.cursor = 'default';
                footerLi.textContent = `Showing 10 of ${list.length} notifications`;
                dropdownMenu.appendChild(footerLi);
            }
        } catch (e) {
            console.error('Failed to load notifications', e);
        }
    }

    function wireNotificationDropdowns() {
        document.querySelectorAll('.icon-btn').forEach(btn => {
            if (btn.querySelector('.bi-bell')) {
                const container = btn.closest('.dropdown');
                if (container) {
                    const menu = container.querySelector('.dropdown-menu');
                    if (menu) {
                        btn.addEventListener('click', () => {
                            loadNotificationDropdown(menu);
                        });
                    }
                }
            }
        });
    }

    if (typeof window !== 'undefined') {
        window.addEventListener('DOMContentLoaded', () => {
            wireNotificationDropdowns();
            document.addEventListener('visibilitychange', () => {
                if (!document.hidden && isPolling) {
                    updateUnreadCount();
                }
            });
        });
    }

    function escapeHtml(str) {
        if (str === null || str === undefined) return '';
        return String(str)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#039;');
    }

    async function download(url, filename) {
        const fullUrl = url.startsWith('http') ? url : `${getBaseUrl()}${url.startsWith('/') ? '' : '/'}${url}`;
        const headers = {};
        if (accessToken) {
            headers['Authorization'] = `Bearer ${accessToken}`;
        }
        let res = await fetch(fullUrl, { headers, credentials: 'same-origin' });
        if (res.status === 401) {
            const refreshRes = await refreshToken();
            if (refreshRes && refreshRes.accessToken) {
                headers['Authorization'] = `Bearer ${refreshRes.accessToken}`;
                res = await fetch(fullUrl, { headers, credentials: 'same-origin' });
            }
        }
        if (!res.ok) {
            let errorText = res.statusText;
            try {
                const errData = await res.json();
                errorText = errData.detail || errData.title || errorText;
            } catch (e) {}
            throw new Error('Download failed: ' + errorText);
        }
        const blob = await res.blob();
        const blobUrl = window.URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = blobUrl;
        if (filename) a.download = filename;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        setTimeout(() => window.URL.revokeObjectURL(blobUrl), 1000);
    }

    async function loadAuthenticatedImage(imgElement, url, fallbackUrl) {
        if (!imgElement) return;
        if (!url) {
            if (fallbackUrl) imgElement.src = fallbackUrl;
            return;
        }
        if (url.startsWith('http') && !url.includes(window.location.host) && !url.startsWith('/')) {
            imgElement.src = url;
            return;
        }
        try {
            const fullUrl = url.startsWith('http') ? url : `${getBaseUrl()}${url.startsWith('/') ? '' : '/'}${url}`;
            const headers = {};
            if (accessToken) {
                headers['Authorization'] = `Bearer ${accessToken}`;
            }
            let res = await fetch(fullUrl, { headers, credentials: 'same-origin' });
            if (res.status === 401) {
                const refreshRes = await refreshToken();
                if (refreshRes && refreshRes.accessToken) {
                    headers['Authorization'] = `Bearer ${refreshRes.accessToken}`;
                    res = await fetch(fullUrl, { headers, credentials: 'same-origin' });
                }
            }
            if (res.ok) {
                const blob = await res.blob();
                imgElement.src = window.URL.createObjectURL(blob);
            } else if (fallbackUrl) {
                imgElement.src = fallbackUrl;
            }
        } catch (e) {
            if (fallbackUrl) imgElement.src = fallbackUrl;
        }
    }

    return {
        request,
        download,
        loadAuthenticatedImage,
        get: (url, opts) => request(url, { ...opts, method: 'GET' }),
        post: (url, body, opts) => request(url, { ...opts, method: 'POST', body: body instanceof FormData ? body : JSON.stringify(body) }),
        put: (url, body, opts) => request(url, { ...opts, method: 'PUT', body: body instanceof FormData ? body : JSON.stringify(body) }),
        patch: (url, body, opts) => request(url, { ...opts, method: 'PATCH', body: body instanceof FormData ? body : JSON.stringify(body) }),
        delete: (url, opts) => request(url, { ...opts, method: 'DELETE' }),
        escapeHtml,
        setToken,
        getToken,
        clearToken,
        refreshToken,
        updateUnreadCount,
        startNotificationPolling,
        stopNotificationPolling
    };
})();
