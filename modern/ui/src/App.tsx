import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { Layout } from './components/Layout'
import { ToastProvider } from './components/Toast'
import { LiveProvider } from './lib/live'
import { ActivityPage } from './pages/Activity'
import { ArchitecturePage } from './pages/Architecture'
import { DriverPage } from './pages/Driver'
import { ParityPage } from './pages/Parity'
import { RegistrationPage } from './pages/Registration'

export function App() {
  return (
    <BrowserRouter>
      <ToastProvider>
        <LiveProvider>
          <Routes>
            <Route element={<Layout />}>
              <Route index element={<Navigate to="/drivers" replace />} />
              <Route path="drivers" element={<DriverPage />} />
              <Route path="drivers/:driverId" element={<DriverPage />} />
              <Route path="activity" element={<ActivityPage />} />
              <Route path="architecture" element={<ArchitecturePage />} />
              <Route path="parity" element={<ParityPage />} />
              <Route path="renewals" element={<RegistrationPage />} />
              <Route path="*" element={<Navigate to="/drivers" replace />} />
            </Route>
          </Routes>
        </LiveProvider>
      </ToastProvider>
    </BrowserRouter>
  )
}
