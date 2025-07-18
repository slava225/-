// Simplified animations without page transitions to prevent flashing
class SimpleAnimations {
    constructor() {
        this.init();
    }

    init() {
        this.animateOnLoad();
        this.setupScrollAnimations();
        this.setupHoverEffects();
    }

    animateOnLoad() {
        // Simple fade in for the page
        document.body.style.opacity = '1';
        
        // Animate elements in with stagger
        const elements = document.querySelectorAll('.feature-card, .team-member, .service-card, .stat-item, .process-step');
        elements.forEach((element, index) => {
            element.style.opacity = '0';
            element.style.transform = 'translateY(20px)';
            element.style.transition = 'all 0.6s ease-out';
            
            setTimeout(() => {
                element.style.opacity = '1';
                element.style.transform = 'translateY(0)';
            }, index * 100);
        });
    }

    setupScrollAnimations() {
        // Intersection Observer for scroll animations
        const observer = new IntersectionObserver((entries) => {
            entries.forEach(entry => {
                if (entry.isIntersecting) {
                    entry.target.classList.add('animate-in');
                    
                    // Animate counters
                    if (entry.target.classList.contains('stat-number')) {
                        this.animateCounter(entry.target);
                    }
                }
            });
        }, {
            threshold: 0.1,
            rootMargin: '0px 0px -50px 0px'
        });

        // Observe elements
        const animatedElements = document.querySelectorAll('.feature-card, .team-member, .service-card, .stat-item');
        animatedElements.forEach(el => observer.observe(el));
    }

    animateCounter(element) {
        const target = parseInt(element.dataset.target) || 0;
        const duration = 2000;
        const stepTime = 50;
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
            setTimeout(updateCounter, stepTime);
        };
        
        updateCounter();
    }

    setupHoverEffects() {
        // Add hover classes to interactive elements
        const hoverElements = document.querySelectorAll('.feature-card, .team-member, .service-card, .btn');
        
        hoverElements.forEach(element => {
            element.addEventListener('mouseenter', () => {
                element.style.transform = 'translateY(-4px)';
            });
            
            element.addEventListener('mouseleave', () => {
                element.style.transform = 'translateY(0)';
            });
        });
    }
}

// Initialize when DOM is ready
if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', () => {
        new SimpleAnimations();
    });
} else {
    new SimpleAnimations();
}