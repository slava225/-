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
        // Get all internal links
        const links = document.querySelectorAll('a[href^="./"], a[href^="index.html"], a[href^="about.html"], a[href^="services.html"], a[href^="contact.html"]');
        
        links.forEach(link => {
            link.addEventListener('click', (e) => {
                e.preventDefault();
                const href = link.getAttribute('href');
                
                if (href && href !== '#') {
                    this.transitionToPage(href);
                }
            });
        });
    }

    transitionToPage(href) {
        // Add exit animation
        document.body.style.opacity = '0.8';
        document.body.style.transform = 'translateX(-20px)';
        document.body.style.transition = 'all 0.3s ease-out';

        // Navigate after animation
        setTimeout(() => {
            window.location.href = href;
        }, 300);
    }

    animateOnLoad() {
        // Add loading animation to body
        document.body.style.opacity = '0';
        document.body.style.transform = 'translateX(20px)';
        
        setTimeout(() => {
            document.body.style.transition = 'all 0.5s ease-out';
            document.body.style.opacity = '1';
            document.body.style.transform = 'translateX(0)';
        }, 100);
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