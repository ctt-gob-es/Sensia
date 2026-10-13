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


import { Component, Input, Output, EventEmitter, signal, ElementRef, HostListener, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../core/services/auth.service';

@Component({
  selector: 'app-header',
  standalone: true,
  imports: [CommonModule, RouterLink],
  templateUrl: './header.component.html',
  styleUrls: ['./header.component.css']
})
export class HeaderComponent {
  @Input() user: any;
  
  @Output() onAccount = new EventEmitter<void>();
  @Output() onSettings = new EventEmitter<void>();
  @Output() onAdmin = new EventEmitter<void>(); 
  @Output() onLogout = new EventEmitter<void>();

  isUserMenuOpen = signal(false);
  private eRef = inject(ElementRef);
  private authService = inject(AuthService);

  toggleUserMenu() {
    this.isUserMenuOpen.update(v => !v);
  }

  isAdmin(): boolean {
    return this.authService.isAdmin();
  }

  @HostListener('document:click', ['$event'])
  clickOut(event: MouseEvent) {
    if (this.isUserMenuOpen() && !this.eRef.nativeElement.contains(event.target)) {
      this.isUserMenuOpen.set(false);
    }
  }
}

