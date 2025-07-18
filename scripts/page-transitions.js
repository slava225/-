// Page transition animations
class PageTransitions {
    constructor() {
        this.init();
    }

    init() {
        this.addPageLoader();
        this.setupLinkTransitions();
        this.animateOnLoad();
    }

    addPageLoader() {
        // Remove loader when page is fully loaded
        window.addEventListener('load', () => {
            const loader = document.getElementById('page-loader');
            if (loader) {
                loader.style.opacity = '1';
                
                // Animate elements in
                this.animateElements();
            }
        });
    }

    animateElements() {
        // Add staggered animations to elements
        const elements = document.querySelectorAll('.feature-card, .team-member, .service-card, .stat-item, .process-step');
        
        elements.forEach((element, index) => {
            setTimeout(() => {
                element.classList.add('animate-in');
            }, index * 100);
        });

        // Animate navigation
        const navLinks = document.querySelectorAll('.nav-link');
        navLinks.forEach((link, index) => {
            setTimeout(() => {
                link.style.animation = `fadeIn 0.5s ease-out ${index * 0.1}s forwards`;
            }, 200);
        });
    }

    setupLinkTransitions() {
        // Get all internal links but exclude external and special links
        const links = document.querySelectorAll('a[href^="./"], a[href^="index.html"], a[href^="about.html"], a[href^="services.html"], a[href^="contact.html"]');
        
        links.forEach(link => {
            // Skip if it's a button in form or has special attributes
            if (link.closest('form') || link.hasAttribute('target') || link.getAttribute('href') === '#') {
                return;
            }
            
            link.addEventListener('click', (e) => {
                const href = link.getAttribute('href');
                
                // Allow normal navigation for same page or hash links
                if (!href || href === '#' || href === window.location.pathname) {
                    return;
                }
                
                // Only prevent default for actual page transitions
                e.preventDefault();
                this.transitionToPage(href);
            });
        });
    }

    transitionToPage(href) {
        // Quick fade out without transform to prevent layout issues
        document.body.style.transition = 'opacity 0.2s ease-out';
        document.body.style.opacity = '0.7';

        // Navigate quickly to prevent old design flash
        setTimeout(() => {
            window.location.href = href;
        }, 150);
    }

    animateOnLoad() {
        // Ensure CSS is loaded first
        document.body.style.opacity = '0';
        
        // Quick reveal with proper styles
        requestAnimationFrame(() => {
            document.body.style.transition = 'opacity 0.3s ease-out';
            document.body.style.opacity = '1';
            document.body.style.transform = 'none';
        });
    }
}

// Initialize when DOM is ready
if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', () => {
        new PageTransitions();
    });
} else {
    new PageTransitions();
}