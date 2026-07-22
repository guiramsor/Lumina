import { createRoot } from 'react-dom/client'
import App from './App.jsx'
import { PlayerProvider } from './player/PlayerContext.jsx'
import './styles.css'

createRoot(document.getElementById('root')).render(
  <PlayerProvider>
    <App />
  </PlayerProvider>
)
