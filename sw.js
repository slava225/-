// Service Worker for ModernSite PWA
const CACHE_NAME = 'modernsite-v1.0.0';

// Determine base path for GitHub Pages
const BASE_PATH = location.pathname.substring(0, location.pathname.lastIndexOf('/') + 1);

const STATIC_ASSETS = [
    BASE_PATH,
    `${BASE_PATH}index.html`,
    `${BASE_PATH}about.html`,
    `${BASE_PATH}services.html`,
    `${BASE_PATH}contact.html`,
    `${BASE_PATH}styles/main.css`,
    `${BASE_PATH}scripts/main.js`,
    `${BASE_PATH}manifest.json`
];

// Install event - cache static assets
self.addEventListener('install', event => {
    console.log('Service Worker: Installing...');
    
    event.waitUntil(
        caches.open(CACHE_NAME)
            .then(cache => {
                console.log('Service Worker: Caching files');
                return cache.addAll(STATIC_ASSETS);
            })
            .then(() => {
                console.log('Service Worker: Installation complete');
                // Take control immediately
                return self.skipWaiting();
            })
            .catch(error => {
                console.error('Service Worker: Installation failed', error);
            })
    );
});

// Activate event - clean up old caches
self.addEventListener('activate', event => {
    console.log('Service Worker: Activating...');
    
    event.waitUntil(
        caches.keys()
            .then(cacheNames => {
                return Promise.all(
                    cacheNames.map(cacheName => {
                        if (cacheName !== CACHE_NAME) {
                            console.log('Service Worker: Deleting old cache', cacheName);
                            return caches.delete(cacheName);
                        }
                    })
                );
            })
            .then(() => {
                console.log('Service Worker: Activation complete');
                // Take control of all pages
                return self.clients.claim();
            })
    );
});

// Fetch event - serve from cache with network fallback
self.addEventListener('fetch', event => {
    // Skip non-GET requests
    if (event.request.method !== 'GET') return;
    
    // Skip requests to other origins
    if (!event.request.url.startsWith(self.location.origin)) return;
    
    event.respondWith(
        caches.match(event.request)
            .then(cachedResponse => {
                // Return cached version if available
                if (cachedResponse) {
                    console.log('Service Worker: Serving from cache', event.request.url);
                    return cachedResponse;
                }
                
                // Fetch from network and cache for future use
                return fetch(event.request)
                    .then(response => {
                        // Check if we received a valid response
                        if (!response || response.status !== 200 || response.type !== 'basic') {
                            return response;
                        }
                        
                        // Clone the response as it can only be consumed once
                        const responseToCache = response.clone();
                        
                        caches.open(CACHE_NAME)
                            .then(cache => {
                                cache.put(event.request, responseToCache);
                                console.log('Service Worker: Cached new resource', event.request.url);
                            });
                        
                        return response;
                    })
                    .catch(error => {
                        console.error('Service Worker: Fetch failed', error);
                        
                        // Return offline fallback for HTML pages
                        if (event.request.destination === 'document') {
                            return caches.match(`${BASE_PATH}index.html`);
                        }
                        
                        // For other resources, just let the error propagate
                        throw error;
                    });
            })
    );
});

// Background sync event (for future use)
self.addEventListener('sync', event => {
    console.log('Service Worker: Background sync triggered', event.tag);
    
    if (event.tag === 'contact-form') {
        event.waitUntil(
            // Handle offline form submissions here
            console.log('Service Worker: Syncing contact form data')
        );
    }
});

// Push notification event (for future use)
self.addEventListener('push', event => {
    console.log('Service Worker: Push notification received');
    
    const options = {
        body: event.data?.text() || 'Новое уведомление от ModernSite',
        icon: '/icons/icon-192.png',
        badge: '/icons/badge-72.png',
        vibrate: [100, 50, 100],
        data: {
            dateOfArrival: Date.now(),
            primaryKey: 1
        },
        actions: [
            {
                action: 'explore',
                title: 'Открыть сайт',
                icon: '/icons/checkmark.png'
            },
            {
                action: 'close',
                title: 'Закрыть',
                icon: '/icons/xmark.png'
            }
        ]
    };
    
    event.waitUntil(
        self.registration.showNotification('ModernSite', options)
    );
});

// Notification click event
self.addEventListener('notificationclick', event => {
    console.log('Service Worker: Notification clicked');
    
    event.notification.close();
    
    if (event.action === 'explore') {
        // Open the website
        event.waitUntil(
            clients.openWindow(BASE_PATH)
        );
    }
});

// Message event for communication with main thread
self.addEventListener('message', event => {
    console.log('Service Worker: Message received', event.data);
    
    if (event.data && event.data.type === 'SKIP_WAITING') {
        self.skipWaiting();
    }
    
    if (event.data && event.data.type === 'GET_VERSION') {
        event.ports[0].postMessage({
            version: CACHE_NAME
        });
    }
});

// Error handling
self.addEventListener('error', event => {
    console.error('Service Worker: Error occurred', event.error);
});

self.addEventListener('unhandledrejection', event => {
    console.error('Service Worker: Unhandled promise rejection', event.reason);
    event.preventDefault();
});