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

// System Admin pages
import { CollegesList } from './pages/admin/colleges/CollegesList'
import { CreateCollege } from './pages/admin/colleges/CreateCollege'
import { CreateCollegeAdmin } from './pages/admin/admins/CreateCollegeAdmin'

// College Admin pages
import { Dashboard } from './pages/admin/dashboard/Dashboard'
import { BatchesList } from './pages/admin/batches/BatchesList'
import { CreateBatch } from './pages/admin/batches/CreateBatch'
import { BatchDetails } from './pages/admin/batches/BatchDetails'
import { CompaniesList } from './pages/admin/companies/CompaniesList'
import { CreateCompany } from './pages/admin/companies/CreateCompany'
import { TrainersList } from './pages/admin/trainers/TrainersList'
import { CreateTrainer } from './pages/admin/trainers/CreateTrainer'
import { StudentsList } from './pages/admin/students/StudentsList'

// Trainer pages
import { TrainerDashboard } from './pages/trainer/dashboard/TrainerDashboard'

// Student pages
import { StudentDashboard } from './pages/student/dashboard/StudentDashboard'

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
            <Dashboard />
          </ProtectedRoute>
        }
      />
      <Route
        path="/admin/batches"
        element={
          <ProtectedRoute>
            <BatchesList />
          </ProtectedRoute>
        }
      />
      <Route
        path="/admin/batches/create"
        element={
          <ProtectedRoute>
            <CreateBatch />
          </ProtectedRoute>
        }
      />
      <Route
        path="/admin/batches/:id"
        element={
          <ProtectedRoute>
            <BatchDetails />
          </ProtectedRoute>
        }
      />
      <Route
        path="/admin/companies"
        element={
          <ProtectedRoute>
            <CompaniesList />
          </ProtectedRoute>
        }
      />
      <Route
        path="/admin/companies/create"
        element={
          <ProtectedRoute>
            <CreateCompany />
          </ProtectedRoute>
        }
      />
      <Route
        path="/admin/trainers"
        element={
          <ProtectedRoute>
            <TrainersList />
          </ProtectedRoute>
        }
      />
      <Route
        path="/admin/trainers/create"
        element={
          <ProtectedRoute>
            <CreateTrainer />
          </ProtectedRoute>
        }
      />
      <Route
        path="/admin/students"
        element={
          <ProtectedRoute>
            <StudentsList />
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
