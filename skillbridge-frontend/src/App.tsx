/**
 * Main App Component
 * 
 * Sets up routing for the entire application
 */

import { Routes, Route, Navigate } from 'react-router-dom'
import { Landing } from './pages/Landing'
import { Login } from './pages/auth/Login'
import { Register } from './pages/auth/Register'
import { ProtectedRoute } from './shared/components/auth'
import { CollegesList } from './pages/admin/colleges/CollegesList'
import { CreateCollege } from './pages/admin/colleges/CreateCollege'
import { CreateCollegeAdmin } from './pages/admin/admins/CreateCollegeAdmin'

function CollegeAdminDashboard() {
  return (
    <div className="p-8">
      <h1 className="text-3xl font-bold">College Admin Dashboard</h1>
      <p className="mt-4 text-muted-foreground">Coming soon...</p>
    </div>
  )
}

function TrainerDashboard() {
  return (
    <div className="p-8">
      <h1 className="text-3xl font-bold">Trainer Dashboard</h1>
      <p className="mt-4 text-muted-foreground">Coming soon...</p>
    </div>
  )
}

function StudentDashboard() {
  return (
    <div className="p-8">
      <h1 className="text-3xl font-bold">Student Dashboard</h1>
      <p className="mt-4 text-muted-foreground">Coming soon...</p>
    </div>
  )
}

function App() {
  return (
    <Routes>
      {/* Public Routes */}
      <Route path="/" element={<Landing />} />
      <Route path="/login" element={<Login />} />
      <Route path="/register" element={<Register />} />

      {/* Protected Routes - System Admin */}
      <Route
        path="/admin/colleges"
        element={
          <ProtectedRoute>
            <CollegesList />
          </ProtectedRoute>
        }
      />
      <Route
        path="/admin/colleges/create"
        element={
          <ProtectedRoute>
            <CreateCollege />
          </ProtectedRoute>
        }
      />
      <Route
        path="/admin/admins/create"
        element={
          <ProtectedRoute>
            <CreateCollegeAdmin />
          </ProtectedRoute>
        }
      />

      {/* Protected Routes - College Admin */}
      <Route
        path="/admin/dashboard"
        element={
          <ProtectedRoute>
            <CollegeAdminDashboard />
          </ProtectedRoute>
        }
      />

      {/* Protected Routes - Trainer */}
      <Route
        path="/trainer/dashboard"
        element={
          <ProtectedRoute>
            <TrainerDashboard />
          </ProtectedRoute>
        }
      />

      {/* Protected Routes - Student */}
      <Route
        path="/student/dashboard"
        element={
          <ProtectedRoute>
            <StudentDashboard />
          </ProtectedRoute>
        }
      />

      {/* Catch all - redirect to home */}
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}

export default App
