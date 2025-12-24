/**
 * Landing Page
 * 
 * Public landing page with:
 * - Hero section
 * - Features overview
 * - Call-to-action buttons (Login/Register)
 */

import { Link } from 'react-router-dom'
import { Button } from '@/shared/components/ui'
import { Header } from '@/shared/components/layout'
import { GraduationCap, Users, BookOpen, Briefcase, ArrowRight } from 'lucide-react'

export function Landing() {
  return (
    <div className="min-h-screen flex flex-col bg-background">
      {/* Header */}
      <Header />

      {/* Main Content */}
      <main className="flex-1">
        {/* Hero Section */}
        <section className="container mx-auto px-4 py-20 md:py-32">
          <div className="max-w-4xl mx-auto text-center">
            <h1 className="text-4xl md:text-6xl font-bold tracking-tight mb-6">
              Bridge the Gap Between
              <span className="text-primary"> Learning and Career</span>
            </h1>
            <p className="text-xl text-muted-foreground mb-8 max-w-2xl mx-auto">
              SkillBridge connects students, trainers, and companies in a unified training
              management platform. Track progress, get recommendations, and land your dream job.
            </p>
            <div className="flex flex-col sm:flex-row gap-4 justify-center">
              <Button asChild size="lg" className="text-lg px-8">
                <Link to="/register">
                  Get Started
                  <ArrowRight className="ml-2 h-5 w-5" />
                </Link>
              </Button>
              <Button asChild size="lg" variant="outline" className="text-lg px-8">
                <Link to="/login">Login</Link>
              </Button>
            </div>
          </div>
        </section>

        {/* Features Section */}
        <section className="container mx-auto px-4 py-20 bg-muted/50">
          <div className="max-w-6xl mx-auto">
            <h2 className="text-3xl font-bold text-center mb-12">
              Everything You Need to Succeed
            </h2>
            <div className="grid md:grid-cols-2 lg:grid-cols-4 gap-8">
              {/* Feature 1 */}
              <div className="text-center">
                <div className="inline-flex items-center justify-center w-16 h-16 rounded-full bg-primary/10 mb-4">
                  <GraduationCap className="h-8 w-8 text-primary" />
                </div>
                <h3 className="text-xl font-semibold mb-2">Student Management</h3>
                <p className="text-muted-foreground">
                  Track your progress, manage skills, and discover training opportunities
                  tailored to your career goals.
                </p>
              </div>

              {/* Feature 2 */}
              <div className="text-center">
                <div className="inline-flex items-center justify-center w-16 h-16 rounded-full bg-primary/10 mb-4">
                  <Users className="h-8 w-8 text-primary" />
                </div>
                <h3 className="text-xl font-semibold mb-2">Trainer Coordination</h3>
                <p className="text-muted-foreground">
                  Manage batches, track student progress, and provide feedback in one
                  centralized platform.
                </p>
              </div>

              {/* Feature 3 */}
              <div className="text-center">
                <div className="inline-flex items-center justify-center w-16 h-16 rounded-full bg-primary/10 mb-4">
                  <BookOpen className="h-8 w-8 text-primary" />
                </div>
                <h3 className="text-xl font-semibold mb-2">Smart Recommendations</h3>
                <p className="text-muted-foreground">
                  Get personalized batch recommendations based on your skills, learning
                  opportunities, and career alignment.
                </p>
              </div>

              {/* Feature 4 */}
              <div className="text-center">
                <div className="inline-flex items-center justify-center w-16 h-16 rounded-full bg-primary/10 mb-4">
                  <Briefcase className="h-8 w-8 text-primary" />
                </div>
                <h3 className="text-xl font-semibold mb-2">Placement Tracking</h3>
                <p className="text-muted-foreground">
                  Connect with companies, track applications, and manage your placement
                  journey from start to finish.
                </p>
              </div>
            </div>
          </div>
        </section>

        {/* CTA Section */}
        <section className="container mx-auto px-4 py-20">
          <div className="max-w-4xl mx-auto text-center bg-primary/5 rounded-lg p-12">
            <h2 className="text-3xl font-bold mb-4">Ready to Get Started?</h2>
            <p className="text-xl text-muted-foreground mb-8">
              Join SkillBridge today and take control of your training journey.
            </p>
            <Button asChild size="lg" className="text-lg px-8">
              <Link to="/register">
                Create Account
                <ArrowRight className="ml-2 h-5 w-5" />
              </Link>
            </Button>
          </div>
        </section>
      </main>
    </div>
  )
}

