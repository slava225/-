// Modern JavaScript using ECMAScript 2025 features
'use strict';

// Using top-level await and dynamic imports
await new Promise(resolve => {
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', resolve);
    } else {
        resolve();
    }
});

// Modern JavaScript classes with private fields and methods
class ModernWebsite {
    // Private fields using # syntax
    #isMenuOpen = false;
    #scrollDirection = 'up';
    #lastScrollTop = 0;
    #intersectionObserver = null;
    #resizeObserver = null;
    
    constructor() {
        this.init();
    }
    
    async init() {
        // Initialize all components
        await Promise.all([
            this.#setupNavigation(),
            this.#setupScrollEffects(),
            this.#setupAnimations(),
            this.#setupForm(),
            this.#setupPerformanceOptimizations()
        ]);
        
        console.log('🚀 ModernWebsite initialized with ES2025 features');
    }
    
    // Private method for navigation setup
    #setupNavigation() {
        const navToggle = document.getElementById('navToggle');
        const navMenu = document.getElementById('navMenu');
        
        if (!navToggle || !navMenu) return;
        
        // Modern event handling with optional chaining
        navToggle?.addEventListener('click', () => {
            this.#toggleMobileMenu();
        });
        
        // Close menu when clicking outside
        document.addEventListener('click', (event) => {
            if (!navToggle.contains(event.target) && !navMenu.contains(event.target)) {
                this.#closeMobileMenu();
            }
        });
        
        // Close menu on escape key
        document.addEventListener('keydown', (event) => {
            if (event.key === 'Escape' && this.#isMenuOpen) {
                this.#closeMobileMenu();
            }
        });
        
        // Handle navigation links
        this.#setupNavLinks();
    }
    
    #setupNavLinks() {
        const navLinks = document.querySelectorAll('.nav-link');
        const currentPage = window.location.pathname.split('/').pop() || 'index.html';
        
        navLinks.forEach(link => {
            const href = link.getAttribute('href');
            if (href === currentPage) {
                link.classList.add('active');
            }
            
            // Smooth scroll for same-page anchors
            if (href?.startsWith('#')) {
                link.addEventListener('click', (e) => {
                    e.preventDefault();
                    const target = document.querySelector(href);
                    target?.scrollIntoView({ 
                        behavior: 'smooth',
                        block: 'start'
                    });
                    this.#closeMobileMenu();
                });
            }
        });
    }
    
    #toggleMobileMenu() {
        const navToggle = document.getElementById('navToggle');
        const navMenu = document.getElementById('navMenu');
        
        this.#isMenuOpen = !this.#isMenuOpen;
        
        navToggle?.classList.toggle('active');
        navMenu?.classList.toggle('active');
        
        // Prevent body scroll when menu is open
        document.body.style.overflow = this.#isMenuOpen ? 'hidden' : '';
        
        // Update ARIA attributes for accessibility
        navToggle?.setAttribute('aria-expanded', this.#isMenuOpen.toString());
    }
    
    #closeMobileMenu() {
        if (!this.#isMenuOpen) return;
        
        const navToggle = document.getElementById('navToggle');
        const navMenu = document.getElementById('navMenu');
        
        this.#isMenuOpen = false;
        navToggle?.classList.remove('active');
        navMenu?.classList.remove('active');
        document.body.style.overflow = '';
        navToggle?.setAttribute('aria-expanded', 'false');
    }
    
    // Modern scroll effects with Intersection Observer
    #setupScrollEffects() {
        this.#setupHeaderScroll();
        this.#setupScrollToTop();
        this.#setupScrollAnimations();
    }
    
    #setupHeaderScroll() {
        const header = document.querySelector('.header');
        if (!header) return;
        
        let ticking = false;
        
        const updateHeader = () => {
            const currentScrollTop = window.pageYOffset || document.documentElement.scrollTop;
            
            // Add scrolled class when not at top
            header.classList.toggle('scrolled', currentScrollTop > 10);
            
            // Update scroll direction
            if (currentScrollTop > this.#lastScrollTop && currentScrollTop > 100) {
                this.#scrollDirection = 'down';
            } else {
                this.#scrollDirection = 'up';
            }
            
            this.#lastScrollTop = currentScrollTop;
            ticking = false;
        };
        
        const requestTick = () => {
            if (!ticking) {
                requestAnimationFrame(updateHeader);
                ticking = true;
            }
        };
        
        window.addEventListener('scroll', requestTick, { passive: true });
    }
    
    #setupScrollToTop() {
        // Create scroll to top button if it doesn't exist
        let scrollToTopBtn = document.querySelector('.scroll-to-top');
        
        if (!scrollToTopBtn) {
            scrollToTopBtn = document.createElement('button');
            scrollToTopBtn.className = 'scroll-to-top';
            scrollToTopBtn.innerHTML = '↑';
            scrollToTopBtn.setAttribute('aria-label', 'Scroll to top');
            document.body.appendChild(scrollToTopBtn);
        }
        
        // Show/hide scroll to top button
        const toggleScrollToTop = () => {
            const shouldShow = window.pageYOffset > 300;
            scrollToTopBtn.classList.toggle('visible', shouldShow);
        };
        
        window.addEventListener('scroll', toggleScrollToTop, { passive: true });
        
        // Scroll to top functionality
        scrollToTopBtn.addEventListener('click', () => {
            window.scrollTo({
                top: 0,
                behavior: 'smooth'
            });
        });
    }
    
    #setupScrollAnimations() {
        // Using modern Intersection Observer for scroll animations
        this.#intersectionObserver = new IntersectionObserver(
            (entries) => {
                entries.forEach(entry => {
                    if (entry.isIntersecting) {
                        entry.target.classList.add('animate-in');
                        
                        // Start counter animation for stat numbers
                        if (entry.target.classList.contains('stat-number')) {
                            this.#animateCounter(entry.target);
                        }
                    }
                });
            },
            {
                threshold: 0.1,
                rootMargin: '0px 0px -50px 0px'
            }
        );
        
        // Observe elements for animation
        const animatedElements = document.querySelectorAll(
            '.feature-card, .team-member, .service-card, .stat-item, .process-step'
        );
        
        animatedElements.forEach(el => {
            this.#intersectionObserver.observe(el);
        });
    }
    
    // Counter animation using modern async/await
    async #animateCounter(element) {
        const target = parseInt(element.dataset.target) || 0;
        const duration = 2000; // 2 seconds
        const stepTime = 16; // ~60fps
        const steps = duration / stepTime;
        const increment = target / steps;
        
        let current = 0;
        
        const updateCounter = () => {
            current += increment;
            if (current >= target) {
                element.textContent = target;
                return;
            }
            
            element.textContent = Math.floor(current);
            requestAnimationFrame(updateCounter);
        };
        
        updateCounter();
    }
    
    // Modern form handling with validation
    #setupForm() {
        const contactForm = document.getElementById('contactForm');
        if (!contactForm) return;
        
        // Add modern form validation
        this.#setupFormValidation(contactForm);
        
        // Handle form submission
        contactForm.addEventListener('submit', async (e) => {
            e.preventDefault();
            
            if (!this.#validateForm(contactForm)) {
                return;
            }
            
            await this.#submitForm(contactForm);
        });
    }
    
    #setupFormValidation(form) {
        const inputs = form.querySelectorAll('input, textarea, select');
        
        inputs.forEach(input => {
            // Real-time validation
            input.addEventListener('blur', () => {
                this.#validateField(input);
            });
            
            // Clear validation on input
            input.addEventListener('input', () => {
                this.#clearFieldValidation(input);
            });
        });
    }
    
    #validateField(field) {
        const value = field.value.trim();
        const isRequired = field.hasAttribute('required');
        const fieldType = field.type;
        
        let isValid = true;
        let errorMessage = '';
        
        // Required field validation
        if (isRequired && !value) {
            isValid = false;
            errorMessage = 'Это поле обязательно для заполнения';
        }
        
        // Email validation
        if (fieldType === 'email' && value) {
            const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
            if (!emailRegex.test(value)) {
                isValid = false;
                errorMessage = 'Введите корректный email адрес';
            }
        }
        
        // Phone validation
        if (fieldType === 'tel' && value) {
            const phoneRegex = /^[\+]?[0-9\s\-\(\)]{10,}$/;
            if (!phoneRegex.test(value)) {
                isValid = false;
                errorMessage = 'Введите корректный номер телефона';
            }
        }
        
        this.#showFieldValidation(field, isValid, errorMessage);
        return isValid;
    }
    
    #showFieldValidation(field, isValid, errorMessage) {
        const formGroup = field.closest('.form-group');
        if (!formGroup) return;
        
        // Remove existing error
        const existingError = formGroup.querySelector('.field-error');
        existingError?.remove();
        
        // Update field styling
        field.classList.toggle('invalid', !isValid);
        field.classList.toggle('valid', isValid && field.value.trim());
        
        // Show error message
        if (!isValid && errorMessage) {
            const errorEl = document.createElement('div');
            errorEl.className = 'field-error';
            errorEl.textContent = errorMessage;
            errorEl.style.cssText = `
                color: var(--color-error);
                font-size: var(--font-size-sm);
                margin-top: var(--space-xs);
            `;
            formGroup.appendChild(errorEl);
        }
    }
    
    #clearFieldValidation(field) {
        field.classList.remove('invalid', 'valid');
        const formGroup = field.closest('.form-group');
        const errorEl = formGroup?.querySelector('.field-error');
        errorEl?.remove();
    }
    
    #validateForm(form) {
        const fields = form.querySelectorAll('input, textarea, select');
        let isFormValid = true;
        
        fields.forEach(field => {
            const isFieldValid = this.#validateField(field);
            if (!isFieldValid) {
                isFormValid = false;
            }
        });
        
        return isFormValid;
    }
    
    async #submitForm(form) {
        const submitBtn = form.querySelector('.submit-btn');
        const btnText = submitBtn?.querySelector('.btn-text');
        const btnLoading = submitBtn?.querySelector('.btn-loading');
        
        // Show loading state
        submitBtn?.setAttribute('disabled', 'true');
        btnText && (btnText.style.display = 'none');
        btnLoading && (btnLoading.style.display = 'flex');
        
        try {
            // Simulate form submission (replace with actual API call)
            await new Promise(resolve => setTimeout(resolve, 2000));
            
            // Show success message
            this.#showNotification('Сообщение успешно отправлено!', 'success');
            form.reset();
            
        } catch (error) {
            // Show error message
            this.#showNotification('Произошла ошибка при отправке формы', 'error');
            console.error('Form submission error:', error);
        } finally {
            // Reset button state
            submitBtn?.removeAttribute('disabled');
            btnText && (btnText.style.display = 'inline');
            btnLoading && (btnLoading.style.display = 'none');
        }
    }
    
    #showNotification(message, type = 'info') {
        // Create notification element
        const notification = document.createElement('div');
        notification.className = `notification notification-${type}`;
        notification.textContent = message;
        
        // Style the notification
        notification.style.cssText = `
            position: fixed;
            top: 20px;
            right: 20px;
            background: ${type === 'success' ? 'var(--color-success)' : 'var(--color-error)'};
            color: white;
            padding: var(--space-md) var(--space-lg);
            border-radius: var(--border-radius);
            box-shadow: var(--shadow-lg);
            z-index: 10000;
            transform: translateX(100%);
            transition: transform var(--transition-normal);
        `;
        
        document.body.appendChild(notification);
        
        // Animate in
        requestAnimationFrame(() => {
            notification.style.transform = 'translateX(0)';
        });
        
        // Auto remove after 5 seconds
        setTimeout(() => {
            notification.style.transform = 'translateX(100%)';
            setTimeout(() => {
                notification.remove();
            }, 300);
        }, 5000);
    }
    
    // Performance optimizations
    #setupPerformanceOptimizations() {
        this.#setupLazyLoading();
        this.#setupPreconnections();
        this.#setupServiceWorker();
    }
    
    #setupLazyLoading() {
        // Lazy load images (if any are added later)
        const images = document.querySelectorAll('img[data-src]');
        
        if ('IntersectionObserver' in window) {
            const imageObserver = new IntersectionObserver((entries) => {
                entries.forEach(entry => {
                    if (entry.isIntersecting) {
                        const img = entry.target;
                        img.src = img.dataset.src;
                        img.classList.remove('lazy');
                        imageObserver.unobserve(img);
                    }
                });
            });
            
            images.forEach(img => imageObserver.observe(img));
        }
    }
    
    #setupPreconnections() {
        // Add preconnect links for external resources
        const preconnectUrls = [
            'https://fonts.googleapis.com',
            'https://fonts.gstatic.com'
        ];
        
        preconnectUrls.forEach(url => {
            const link = document.createElement('link');
            link.rel = 'preconnect';
            link.href = url;
            document.head.appendChild(link);
        });
    }
    
    async #setupServiceWorker() {
        // Register service worker for PWA capabilities
        if ('serviceWorker' in navigator) {
            try {
                const registration = await navigator.serviceWorker.register('/sw.js');
                console.log('Service Worker registered:', registration);
            } catch (error) {
                console.log('Service Worker registration failed:', error);
            }
        }
    }
    
    // Cleanup method
    destroy() {
        this.#intersectionObserver?.disconnect();
        this.#resizeObserver?.disconnect();
        
        // Remove event listeners
        window.removeEventListener('scroll', this.#handleScroll);
        window.removeEventListener('resize', this.#handleResize);
    }
}

// Modern error handling with try-catch
class ErrorHandler {
    static handleError(error, context = 'Unknown') {
        console.error(`Error in ${context}:`, error);
        
        // Report to analytics in production
        if (window.gtag) {
            gtag('event', 'exception', {
                description: error.message,
                fatal: false
            });
        }
    }
}

// Utility functions using modern JavaScript features
const utils = {
    // Debounce function using arrow syntax
    debounce: (func, wait) => {
        let timeout;
        return (...args) => {
            clearTimeout(timeout);
            timeout = setTimeout(() => func.apply(this, args), wait);
        };
    },
    
    // Throttle function
    throttle: (func, limit) => {
        let inThrottle;
        return (...args) => {
            if (!inThrottle) {
                func.apply(this, args);
                inThrottle = true;
                setTimeout(() => inThrottle = false, limit);
            }
        };
    },
    
    // Modern device detection
    getDeviceInfo: () => ({
        isMobile: /Android|webOS|iPhone|iPad|iPod|BlackBerry|IEMobile|Opera Mini/i.test(navigator.userAgent),
        isTablet: /iPad|Android(?=.*Tablet)/i.test(navigator.userAgent),
        supportsTouch: 'ontouchstart' in window,
        supportsWebP: async () => {
            const webP = new Image();
            webP.src = 'data:image/webp;base64,UklGRjoAAABXRUJQVlA4IC4AAACyAgCdASoCAAIALmk0mk0iIiIiIgBoSygABc6WWgAA/veff/0PP8bA//LwYAAA';
            return new Promise(resolve => {
                webP.onload = webP.onerror = () => resolve(webP.height === 2);
            });
        }
    }),
    
    // Modern local storage with error handling
    storage: {
        set: (key, value) => {
            try {
                localStorage.setItem(key, JSON.stringify(value));
                return true;
            } catch (error) {
                console.warn('LocalStorage not available:', error);
                return false;
            }
        },
        
        get: (key, defaultValue = null) => {
            try {
                const item = localStorage.getItem(key);
                return item ? JSON.parse(item) : defaultValue;
            } catch (error) {
                console.warn('LocalStorage read error:', error);
                return defaultValue;
            }
        },
        
        remove: (key) => {
            try {
                localStorage.removeItem(key);
                return true;
            } catch (error) {
                console.warn('LocalStorage remove error:', error);
                return false;
            }
        }
    }
};

// Initialize the website
try {
    const website = new ModernWebsite();
    
    // Make utils globally available
    window.utils = utils;
    
    // Handle page visibility changes for performance
    document.addEventListener('visibilitychange', () => {
        if (document.visibilityState === 'hidden') {
            // Pause expensive operations when page is hidden
            console.log('Page hidden - pausing animations');
        } else {
            // Resume operations when page is visible
            console.log('Page visible - resuming animations');
        }
    });
    
} catch (error) {
    ErrorHandler.handleError(error, 'Website initialization');
}

// Modern module export (if using modules)
export { ModernWebsite, utils, ErrorHandler };