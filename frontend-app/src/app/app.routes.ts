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


import { inject } from '@angular/core';
import { Router, Routes } from '@angular/router';
import { AuthService } from './core/services/auth.service';
import { LoginComponent } from './features/login/login.component';
import { DocUploadComponent } from './features/doc-upload/doc-upload.component';
import { authGuard } from './core/guards/auth-guard';
import { DocRevisionComponent } from './features/doc-revision/doc-revision.component';
import { AdminPanelComponent } from './features/admin-panel.component/admin-panel.component';
import { UserSettingsComponent } from './features/user-settings/user-settings.component';
import { UserProfileComponent } from './features/user-profile/user-profile.component';
import { DocComparatorComponent } from './features/doc-comparator/doc-comparator.component';


export const routes: Routes = [
 // Ruta pública
  { path: 'login', component: LoginComponent },

  // Rutas protegidas
  {
    path: '',
    canActivate: [authGuard], // Se aplica a todas las hijas
    children: [
      { path: 'upload', component: DocUploadComponent },
      { path: 'revision', component: DocRevisionComponent },
      { path: 'compare', component: DocComparatorComponent },
      { path: 'account', component: UserProfileComponent },
      { path: 'settings', component: UserSettingsComponent },
      {
        path: 'admin', component: AdminPanelComponent,
        canActivate: [() => inject(AuthService).isAdmin() || inject(Router).createUrlTree(['/upload'])]
      },
      { path: '', redirectTo: 'upload', pathMatch: 'full' }
    ]
  },

  // Catch-all
  { path: '**', redirectTo: 'login' }
];
