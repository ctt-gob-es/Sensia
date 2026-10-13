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

import { Component, OnInit, signal, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, FormGroup, Validators, ReactiveFormsModule, AbstractControl, ValidationErrors } from '@angular/forms';
import { Router } from '@angular/router';
import { UserService } from '../../core/services/user.service'; 
import { AuthService } from '../../core/services/auth.service';

@Component({
  selector: 'app-user-profile',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule],
  templateUrl: './user-profile.component.html',
  styleUrls: ['./user-profile.component.css']
})
export class UserProfileComponent implements OnInit {
  passwordForm!: FormGroup;
  isSaving = signal(false);
  successMessage = signal<string | null>(null);
  errorMessage = signal<string | null>(null);
showCurrent = signal(false);
showNew = signal(false);
showConfirm = signal(false);

  private fb = inject(FormBuilder);
  private router = inject(Router);
  private userService = inject(UserService);
  private authService = inject(AuthService);

  ngOnInit() {
    this.passwordForm = this.fb.group({
      currentPassword: ['', Validators.required],
      newPassword: ['', [Validators.required, Validators.minLength(12)]],
      confirmPassword: ['', Validators.required]
    }, { validators: this.passwordsMatchValidator });
  }

  passwordsMatchValidator(control: AbstractControl): ValidationErrors | null {
    const newPassword = control.get('newPassword')?.value;
    const confirmPassword = control.get('confirmPassword')?.value;
    return newPassword === confirmPassword ? null : { passwordsMismatch: true };
  }

  updatePassword() {
    if (this.passwordForm.invalid) return;

    this.isSaving.set(true);
    this.successMessage.set(null);
    this.errorMessage.set(null);
    

    const { currentPassword, newPassword } = this.passwordForm.value;

    this.userService.updatePassword({ currentPassword, newPassword }).subscribe({
      next: (res) => {
        this.isSaving.set(false);
        this.successMessage.set(res.message || 'Contraseña actualizada correctamente.');
        this.passwordForm.reset();
      },
      error: (err) => {
        this.isSaving.set(false);
        this.errorMessage.set(err.error?.error || 'Error al actualizar la contraseña. Verifica tu contraseña actual.');
      }
    });
  }

  deleteAccount() {
    const isConfirmed = window.confirm(
      '⚠️ ADVERTENCIA: Esta acción es irreversible.\n\n¿Estás absolutamente seguro de que deseas eliminar tu cuenta y todos tus datos?'
    );

    if (isConfirmed) {
      this.userService.deleteMyAccount().subscribe({
        next: (res) => {
          alert(res.message || 'Tu cuenta ha sido eliminada permanentemente.');
          this.authService.logout();
          this.router.navigate(['/login']);
        },
        error: (err) => {
          alert(err.error?.error || 'Error al eliminar la cuenta.');
        }
      });
    }
  }



  isAdmin(): boolean {
    return this.authService.isAdmin();
  }

  
  goBack() {
    this.router.navigate(['/upload']);
  }
}