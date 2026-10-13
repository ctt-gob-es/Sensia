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

import { Component, HostListener, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, FormGroup, Validators, ReactiveFormsModule } from '@angular/forms';
import { UserService } from '../../core/services/user.service';
import { Router } from '@angular/router';
import { AuthService } from '../../core/services/auth.service';

@Component({
  selector: 'app-admin-panel',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule],
  templateUrl: './admin-panel.component.html',
  styleUrls: ['./admin-panel.component.css']
})
export class AdminPanelComponent implements OnInit {
  users = signal<any[]>([]);
  userForm: FormGroup;
  isEditing = signal(false);
  selectedUserId: number | null = null;
  showCurrent = signal(false);
  maintenanceMode = signal(false);
  registrationEnabled = signal(false);
  passwordRecoveryEnabled = signal(false);
  captchaEnabled = signal(false);

  isModalOpen = signal(false);

  openMenuId = signal<number | null>(null);


  constructor(private fb: FormBuilder, private userService: UserService, private router: Router, private authService: AuthService) {
    this.userForm = this.fb.group({
      username: ['', Validators.required],
      email: ['', [Validators.required, Validators.email]],
      role: ['user', Validators.required],
      password: ['']
    });
  }

  ngOnInit() {
    this.loadConfig();
    this.loadUsers();
  }

  @HostListener('document:click', ['$event'])
  onDocumentClick(event: Event) {
    this.openMenuId.set(null);
  }

  toggleMenu(userId: number, event: Event) {
    event.stopPropagation(); // Evita que el clic propague y dispare el @HostListener
    this.openMenuId.set(this.openMenuId() === userId ? null : userId);
  }

  goBack() {
    this.router.navigate(['/upload']);
  }

  loadUsers() {
    this.userService.getUsers().subscribe(data => this.users.set(data));
  }


  loadConfig() {
    this.authService.checkServerStatus().subscribe({
      next: (res) => {
        this.maintenanceMode.set(res.features.maintenanceMode);
        this.captchaEnabled.set(res.features.captchaEnabled);
        this.registrationEnabled.set(res.features.registrationEnabled);
        this.passwordRecoveryEnabled.set(res.features.passwordRecoveryEnabled);
      }, error: (err) => alert('Error al recuperar la configuración')
    });
  }


  openCreateUserModal() {
    this.resetForm();
    this.isEditing.set(false);
    this.isModalOpen.set(true);
  }


  onSubmit() {
    if (this.userForm.invalid) return;

    if (this.isEditing()) {
      this.userService.updateUser(this.selectedUserId!, this.userForm.value).subscribe({
        next: () => {
          this.resetForm();
          this.loadUsers();
        },
        error: (err) => alert(err.error?.error || 'Ocurrió un error al actualizar el usuario')
      });
    } else {
      this.userService.createUser(this.userForm.value).subscribe({
        next: () => {
          this.resetForm();
          this.loadUsers();
        },
        error: (err) => alert(err.error?.error || 'Ocurrió un error al crear el usuario')
      });
    }
  }

  editUser(user: any) {
    this.isEditing.set(true);
    this.openMenuId.set(null);
    this.selectedUserId = user.id;
    this.userForm.patchValue({
      username: user.username,
      email: user.email,
      role: user.role,
      password: ''
    });

    this.isModalOpen.set(true);
  }

  deleteUser(id: number) {
    this.openMenuId.set(null);
    if (confirm('¿Estás seguro de eliminar este usuario permanentemente?')) {
      this.userService.deleteUser(id).subscribe({
        next: () => this.loadUsers(),
        error: (err) => alert(err.error?.error || 'Ocurrió un error al eliminar el usuario')
      });
    }
  }

  toggleBlock(id: number) {
    this.openMenuId.set(null);
    this.userService.toggleBlock(id).subscribe({
      next: () => this.loadUsers(),
      error: (err) => alert(err.error?.error || 'Ocurrió un error al cambiar el estado')
    });
  }


  toggleConfig(key: string, currentValue: boolean) {
    const payload = { [key]: !currentValue };

    this.userService.updateSystemConfig(payload).subscribe({
      next: (res: any) => {
        this.maintenanceMode.set(res.config.maintenanceMode);
        this.registrationEnabled.set(res.config.registrationEnabled);
        this.passwordRecoveryEnabled.set(res.config.passwordRecoveryEnabled);
        this.captchaEnabled.set(res.config.captchaEnabled);
      },
      error: (err) => alert('Error al actualizar la configuración')
    });
  }



  resetForm() {
    this.isEditing.set(false);
    this.selectedUserId = null;
    this.userForm.reset({ role: 'user' });
    this.isModalOpen.set(false);
  }

  async copyPassword(): Promise<void> {
  const password = this.userForm.get('password')?.value;

  if (!password) {
    return;
  }

  await navigator.clipboard.writeText(password);
}


  generatePassword(length = 16): void {
    const uppercase = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ';
    const lowercase = 'abcdefghijklmnopqrstuvwxyz';
    const numbers = '0123456789';
    const symbols = '!@#$%&*_-+=?';

    const chars = uppercase + lowercase + numbers + symbols;

    const password = [
      this.getRandomChar(uppercase),
      this.getRandomChar(lowercase),
      this.getRandomChar(numbers),
      this.getRandomChar(symbols),
    ];

    while (password.length < length) {
      password.push(this.getRandomChar(chars));
    }

    for (let i = password.length - 1; i > 0; i--) {
      const j = this.randomInt(i + 1);
      [password[i], password[j]] = [password[j], password[i]];
    }

    this.userForm.get('password')?.setValue(password.join(''));
  }

  private getRandomChar(chars: string): string {
    return chars[this.randomInt(chars.length)];
  }

  private randomInt(max: number): number {
    const array = new Uint32Array(1);
    crypto.getRandomValues(array);
    return array[0] % max;
  }
}