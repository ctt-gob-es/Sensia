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

import { Component, CUSTOM_ELEMENTS_SCHEMA, ElementRef, inject, signal, ViewChild, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, FormGroup, Validators, ReactiveFormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { AuthService } from '../../core/services/auth.service';
import 'altcha';
import { EnvironmentService } from '../../core/services/environment.service';


type ViewMode = 'login' | 'forgot' | 'reset' | 'register';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule],
  templateUrl: './login.component.html',
  styleUrls: ['./login.component.css'],
  schemas: [CUSTOM_ELEMENTS_SCHEMA]
})
export class LoginComponent implements OnInit {
  loginForm: FormGroup;
  errorMessage = signal<string | null>(null);
  passwordVisible: boolean = false;
  altchaPayload: any | null;
  maintenanceMode = signal(false);
  captchaEnabled = signal(false);
  registrationEnabled = signal(false);
  passwordRecoveryEnabled = signal(false);
  viewMode: ViewMode = 'login';

  private envConfig = inject(EnvironmentService);
  challengeUrl = `${this.envConfig.apiUrl()}/auth/altcha-challenge`;

  @ViewChild('altchaWidget') altchaWidget!: ElementRef;

  constructor(
    private fb: FormBuilder,
    private authService: AuthService,
    private router: Router,
    private route: ActivatedRoute
  ) {
    this.loginForm = this.fb.group({
      email: ['', [Validators.required, Validators.email]],
      password: ['', [Validators.required]],
      token: [''],
      altcha: ['', [Validators.required]]
    });
  }

  ngOnInit() {
    this.authService.checkServerStatus().subscribe({
      next: (res) => {
        if (res.status === 'maintenance') {
          this.errorMessage.set(res.message);
          if (res.type === 'hard') this.loginForm.disable();
        }
        if (res.features) {
          this.maintenanceMode.set(res.features.maintenanceMode);
          this.captchaEnabled.set(res.features.captchaEnabled);
          this.registrationEnabled.set(res.features.registrationEnabled);
          this.passwordRecoveryEnabled.set(res.features.passwordRecoveryEnabled);
          const altchaControl = this.loginForm.get('altcha');
          if (res.features.captchaEnabled) {
            altchaControl?.setValidators([Validators.required]);
          } else {
            altchaControl?.clearValidators();
          }
          altchaControl?.updateValueAndValidity();
        }

      },
      error: (err) => {
        if (err.status === 503) {
          this.errorMessage.set(err.error?.error || 'Sistema fuera de servicio técnico.');
          this.loginForm.disable();
        }
      }
    });
  }

  togglePasswordVisibility(): void {
    this.passwordVisible = !this.passwordVisible;
  }

  setViewMode(mode: ViewMode): void {
    this.viewMode = mode;
    this.errorMessage.set(null);

    this.loginForm.reset();

    const passwordControl = this.loginForm.get('password');
    const tokenControl = this.loginForm.get('token');

    passwordControl?.clearValidators();
    tokenControl?.clearValidators();

    if (mode === 'login') {
      // Al entrar no se exige longitud mínima; se exige al fijar una contraseña nueva
      passwordControl?.setValidators([Validators.required]);
    } else if (mode === 'reset') {
      passwordControl?.setValidators([Validators.required, Validators.minLength(12)]);
      tokenControl?.setValidators([Validators.required]);
    }

    passwordControl?.updateValueAndValidity();
    tokenControl?.updateValueAndValidity();

    this.resetAltchaWidget();
  }

  onAltchaStateChange(event: any) {
    this.altchaPayload = null;
    if (event.detail.state === 'verified') {
      this.loginForm.patchValue({ altcha: event.detail.payload });
      this.altchaPayload = event.detail.payload;
    } else {
      this.loginForm.patchValue({ altcha: null });
    }
  }

  handleSubmit(): void {
    this.loginForm.markAllAsTouched();

    if (this.loginForm.invalid) {
      return;
    }

    if (!this.altchaPayload && this.captchaEnabled()) {
      this.errorMessage.set('La verificación de seguridad es obligatoria.');
      return;
    }

    this.errorMessage.set(null);

    // Distribuidor según el estado
    switch (this.viewMode) {
      case 'login': return this.doLogin();
      case 'forgot': return this.doForgotPassword();
      case 'reset': return this.doResetPassword();
      case 'register': return this.doRegister();
    }
  }

  private doLogin(): void {
    const credentials = { email: this.loginForm.value.email, password: this.loginForm.value.password, altcha: this.loginForm.value.altcha };

    this.authService.login(credentials).subscribe({
      next: (response) => {
        if (response.auth) {
          this.router.navigate(['/upload']);
        } else {
          this.handleAuthError({ status: 401 });
        }
      },
      error: (err) => this.handleAuthError(err)
    });
  }

  private doForgotPassword(): void {
    const { email, altcha } = this.loginForm.value;

    this.authService.forgotPassword(email, altcha).subscribe({
      next: () => {
        this.setViewMode('reset');
        this.loginForm.patchValue({ email });
        this.errorMessage.set('Revisa tu bandeja de entrada. Se ha enviado un código temporal.');
      },
      error: () => {
        this.errorMessage.set('Error al solicitar la clave temporal.');
        this.resetAltchaWidget();
      }
    });
  }

  private doResetPassword(): void {
    const { email, token, password, altcha } = this.loginForm.value;

    this.authService.resetPassword(email, token, password, altcha).subscribe({
      next: () => {
        this.errorMessage.set('Contraseña cambiada con éxito. Ya puede iniciar sesión.');
        setTimeout(() => this.setViewMode('login'), 2000);
      },
      error: (err: { error: { error: any; }; }) => {
        this.errorMessage.set(err.error?.error || 'Error al cambiar la contraseña. Revisa el código temporal.');      
        this.resetAltchaWidget();
      }
    });
  }

  private doRegister(): void {
    const { email, altcha } = this.loginForm.value;

    this.authService.register(email, altcha).subscribe({
      next: () => {
        this.errorMessage.set('¡Cuenta creada! Revisa tu correo para obtener tus credenciales.');
        setTimeout(() => this.setViewMode('login'), 4000);
      },
      error: (err) => {
        if (err.status === 409) {
          this.errorMessage.set('Ese email ya está registrado.');
        } else {
          this.errorMessage.set('Error al crear la cuenta. Inténtalo más tarde.');
        }
        this.resetAltchaWidget();
      }
    });
  }

  private handleAuthError(err: any): void {
    if (err.status === 503) {
      this.errorMessage.set(err.error?.error || 'Sistema en mantenimiento.');
    } else if (err.status === 403) {
      this.errorMessage.set('Tu cuenta está bloqueada. Contacta con soporte.');
    } else if (err.status === 401) {
      this.errorMessage.set('Usuario o contraseña incorrectos.');
    } else {
      this.errorMessage.set('Error técnico. Inténtelo más tarde.');
    }
    this.resetAltchaWidget();
  }

  private resetAltchaWidget(): void {
    this.loginForm.get('altcha')?.setValue(null);
    this.altchaPayload = null;
    if (this.altchaWidget && this.altchaWidget.nativeElement) {
      this.altchaWidget.nativeElement.reset();
    }
  }
}