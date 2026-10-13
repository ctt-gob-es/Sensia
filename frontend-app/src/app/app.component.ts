/*
 * Este archivo es parte del proyecto SensIA.
 * Repositorio oficial: https://github.com/ctt-gob-es/sensia
 *
 * SensIA se distribuye bajo los términos de la Licencia Pública 
 * de la Unión Europea (EUPL) v1.2
 *
 * Consulta el archivo LICENSE en la raíz del proyecto para ver los 
 * detalles legales completos, o visita:
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 */

import { Component, computed, inject, signal } from '@angular/core';
import { NavigationEnd, Router, RouterOutlet } from '@angular/router';
import { HeaderComponent } from './features/header/header.component';
import { AuthService } from './core/services/auth.service';
import { filter } from 'rxjs';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'app-root',
  imports: [CommonModule, RouterOutlet, HeaderComponent],
  templateUrl: './app.component.html',
  styleUrl: './app.component.css'
})
export class AppComponent {

  protected title = 'SensIA';

  private router = inject(Router);
  authService = inject(AuthService);

  currentUrl = signal('');

  showHeader = computed(() => this.currentUrl() !== '/login');

  constructor() {
    this.router.events.pipe(
      filter(event => event instanceof NavigationEnd)
    ).subscribe((event: any) => {
      this.currentUrl.set(event.urlAfterRedirects);
    });
  }

  handleLogout() {
    this.authService.logout();
    this.router.navigate(['/login']); 
  }

  goToSettings() {
    this.router.navigate(['/settings']); 
  }

  goToAccount() {
    this.router.navigate(['/account']); 
  }


  goToAdmin() {
    this.router.navigate(['/admin']); 
  }
}
